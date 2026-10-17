package cn.shopledger.app;

import android.app.Activity;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.webkit.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

public class MainActivity extends Activity {
 private static final String ORIGIN="https://appassets.androidplatform.net/";
 private WebView web;private LedgerDB db;private final Store store=new Store();
 private volatile boolean fileBusy=false;private String pendingText;
 private class LedgerDB extends SQLiteOpenHelper {
  LedgerDB(){super(MainActivity.this,"ledger.db",null,2);}
  void create(SQLiteDatabase d){d.execSQL("CREATE TABLE IF NOT EXISTS books(id INTEGER PRIMARY KEY CHECK(id=1),content TEXT NOT NULL,revision INTEGER NOT NULL,recovery TEXT NOT NULL DEFAULT '')");}
  public void onCreate(SQLiteDatabase d){create(d);}
  public void onUpgrade(SQLiteDatabase d,int oldVersion,int newVersion){create(d);if(oldVersion==1)migrate(d);}
  void migrate(SQLiteDatabase d){
   try{
    JSONObject book=new JSONObject().put("name","我的店铺").put("loans",new JSONArray()).put("reminders",new JSONArray()).put("categories",new JSONArray());
    JSONArray entries=new JSONArray();
    try(Cursor c=d.rawQuery("SELECT value FROM meta WHERE key='name'",null)){if(c.moveToFirst())book.put("name",c.getString(0));}
    try(Cursor c=d.rawQuery("SELECT * FROM entries ORDER BY date DESC,rowid DESC",null)){
     while(c.moveToNext()){
      String type=c.getString(c.getColumnIndexOrThrow("type")),category=c.getString(c.getColumnIndexOrThrow("category")),party=c.getString(c.getColumnIndexOrThrow("party"));
      JSONObject e=new JSONObject().put("id",c.getString(c.getColumnIndexOrThrow("id"))).put("type",type).put("category",category).put("cents",c.getLong(c.getColumnIndexOrThrow("cents"))).put("date",c.getString(c.getColumnIndexOrThrow("date"))).put("note",c.getString(c.getColumnIndexOrThrow("note")));
      if(type.equals("income")){e.put("incomeKind","fixed");if(!party.isEmpty()&&party.length()<=30)e.put("category",party);}
      else if(!party.isEmpty()&&party.length()<=30)e.put("item",party);
      entries.put(e);
     }
    }
    book.put("entries",entries);ContentValues value=new ContentValues();value.put("id",1);value.put("content",book.toString());value.put("revision",0);d.insertOrThrow("books",null,value);
   }catch(Exception e){throw new IllegalStateException("旧版账本升级失败，原账目已保留",e);}
  }
 }
 @Override public void onCreate(Bundle saved){
  super.onCreate(saved);getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);db=new LedgerDB();
  web=new WebView(this);setContentView(web);WebView.setWebContentsDebuggingEnabled(false);
  web.setOnApplyWindowInsetsListener((view,insets)->{
   if(android.os.Build.VERSION.SDK_INT>=30){android.graphics.Insets bars=insets.getInsets(android.view.WindowInsets.Type.systemBars()|android.view.WindowInsets.Type.displayCutout());view.setPadding(bars.left,bars.top,bars.right,bars.bottom);}
   else view.setPadding(insets.getStableInsetLeft(),insets.getStableInsetTop(),insets.getStableInsetRight(),insets.getStableInsetBottom());
   return insets;
  });
  WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setAllowFileAccess(false);settings.setAllowContentAccess(false);settings.setAllowFileAccessFromFileURLs(false);settings.setAllowUniversalAccessFromFileURLs(false);settings.setBlockNetworkLoads(true);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return true;}
   @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return true;}
   @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));}
  });
  web.addJavascriptInterface(store,"Native");
  try(InputStream in=getAssets().open("index.html");ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);web.loadDataWithBaseURL(ORIGIN,out.toString("UTF-8"),"text/html","UTF-8",null);}catch(Exception e){new android.app.AlertDialog.Builder(this).setTitle("无法打开账本").setMessage("应用资源读取失败，请重新安装。").setPositiveButton("关闭",(d,w)->finish()).show();}
 }
 private String error(String message){try{return new JSONObject().put("error",message).toString();}catch(Exception ignored){return "{\"error\":\"操作失败\"}";}}
 private JSONObject emptyBook()throws JSONException{return new JSONObject().put("name","我的店铺").put("entries",new JSONArray()).put("loans",new JSONArray()).put("reminders",new JSONArray()).put("categories",new JSONArray());}
 public class Store {
  @JavascriptInterface public synchronized String readBook(){
   try(Cursor c=db.getReadableDatabase().rawQuery("SELECT content,revision FROM books WHERE id=1",null)){return c.moveToFirst()?new JSONObject().put("book",new JSONObject(c.getString(0))).put("revision",c.getLong(1)).toString():new JSONObject().put("book",emptyBook()).put("revision",-1).toString();}catch(Exception e){return error("本机账本无法读取，原数据未被覆盖");}
  }
  @JavascriptInterface public synchronized String saveBook(String text,long expectedRevision,String recovery){
   if(text==null||text.getBytes(StandardCharsets.UTF_8).length>1500000)return error("账本容量已满，请先导出备份");
   SQLiteDatabase database=null;boolean transaction=false;
   try{
    JSONObject b=new JSONObject(text);if(b.getJSONArray("entries").length()>10000||b.getJSONArray("loans").length()>1000||!b.has("reminders")||!b.has("categories"))return error("账本格式无效");
    if(recovery==null||recovery.getBytes(StandardCharsets.UTF_8).length>2000000)return error("恢复前备份无效");
    if(!recovery.isEmpty())new JSONObject(recovery);
    database=db.getWritableDatabase();database.beginTransaction();transaction=true;long actual=-1;
    try(Cursor c=database.rawQuery("SELECT revision FROM books WHERE id=1",null)){if(c.moveToFirst())actual=c.getLong(0);}
    if(actual!=expectedRevision)return error("账本已更新，请重试保存");
    ContentValues values=new ContentValues();values.put("content",text);values.put("revision",actual+1);if(!recovery.isEmpty())values.put("recovery",recovery);
    if(actual==-1){values.put("id",1);database.insertOrThrow("books",null,values);}else if(database.update("books",values,"id=1 AND revision=?",new String[]{String.valueOf(actual)})!=1)return error("账本已更新，请重试");
    database.setTransactionSuccessful();return new JSONObject().put("revision",actual+1).toString();
   }catch(Exception e){return error("保存失败，请检查手机剩余空间，填写内容已保留");}finally{if(transaction)database.endTransaction();}
  }
  @JavascriptInterface public synchronized String readRecovery(){try(Cursor c=db.getReadableDatabase().rawQuery("SELECT recovery FROM books WHERE id=1",null)){return c.moveToFirst()?c.getString(0):"";}catch(Exception e){return "";}}
  @JavascriptInterface public synchronized String exportFile(String text,String name,String mime){
   if(fileBusy)return error("请先完成当前文件操作");
   if(text==null||text.getBytes(StandardCharsets.UTF_8).length>12000000||!("application/json;charset=utf-8".equals(mime)||"text/csv;charset=utf-8".equals(mime)||"application/json".equals(mime)))return error("导出文件无效");
   fileBusy=true;pendingText=text;final String filename=name.replaceAll("[/\\\\\\r\\n]","_");final String contentType=mime.split(";")[0];
   runOnUiThread(()->{Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(contentType).putExtra(Intent.EXTRA_TITLE,filename);try{startActivityForResult(intent,11);}catch(Exception e){fileBusy=false;pendingText=null;fileResult("无法打开文件保存器，请重试");}});return "{}";
  }
  @JavascriptInterface public synchronized String chooseBackup(){if(fileBusy)return error("请先完成当前文件操作");fileBusy=true;runOnUiThread(()->{Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/json","text/plain","application/octet-stream"});try{startActivityForResult(intent,12);}catch(Exception e){fileBusy=false;fileResult("无法打开文件选择器，请重试");}});return "{}";}
 }
 private void sendEvent(String name,JSONObject detail){runOnUiThread(()->{if(web!=null&&!isFinishing()&&!isDestroyed())web.evaluateJavascript("window.dispatchEvent(new CustomEvent("+JSONObject.quote(name)+",{detail:"+detail.toString()+"}))",null);});}
 private void fileResult(String message){try{sendEvent("ledger-file-result",new JSONObject().put("message",message));}catch(Exception ignored){}}
 @Override protected void onActivityResult(int request,int result,Intent data){
  super.onActivityResult(request,result,data);if(request!=11&&request!=12)return;
  if(result!=RESULT_OK||data==null||data.getData()==null){fileBusy=false;pendingText=null;fileResult("已取消文件操作");return;}
  Uri uri=data.getData();String text=pendingText;pendingText=null;
  new Thread(()->{
   try{
    if(request==11){try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null||text==null)throw new IOException();out.write(text.getBytes(StandardCharsets.UTF_8));}fileResult("文件已保存");}
    else{
     byte[] content;try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException();byte[] buffer=new byte[8192];int count;while((count=in.read(buffer))!=-1){out.write(buffer,0,count);if(out.size()>2000000)throw new IOException("备份超过 2 MB");}content=out.toByteArray();}
     String name="账本备份.json";try(Cursor c=getContentResolver().query(uri,new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}
     sendEvent("ledger-import-file",new JSONObject().put("text",new String(content,StandardCharsets.UTF_8)).put("name",name));
    }
   }catch(Exception e){fileResult(request==11?"文件保存失败，请重新选择位置":"备份无法读取，最多支持 2 MB");}finally{fileBusy=false;}
  }).start();
 }
 @Override protected void onPause(){if(web!=null){web.evaluateJavascript("window.dispatchEvent(new Event('ledger-native-lock'))",null);web.onPause();}super.onPause();}
 @Override protected void onResume(){super.onResume();if(web!=null){web.onResume();web.evaluateJavascript("window.dispatchEvent(new Event('ledger-native-resume'))",null);}}
 @Override public void onBackPressed(){web.evaluateJavascript("window.ledgerHandleBack ? window.ledgerHandleBack() : false",value->{if(!"true".equals(value))MainActivity.super.onBackPressed();});}
 @Override protected void onDestroy(){if(web!=null){web.removeJavascriptInterface("Native");web.destroy();}synchronized(store){if(db!=null)db.close();}super.onDestroy();}
}
