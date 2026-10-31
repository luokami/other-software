import {BookOpen,ChevronLeft,ChevronRight,Database,FileText,Users,X} from 'lucide-react';
import {useEffect,useState} from 'react';
import {Dialog,DialogContent,DialogDescription,DialogTitle} from './ui/dialog';

export const GUIDE_SEEN_KEY='ledger-first-use-guide-v1';
const steps=[
 {title:'从首页，记好第一笔',icon:FileText,description:'点击蔬菜、房租等支出项目，填写具体是什么、日期和金额，点“记好这一笔”就完成了。',hint:'收入分为固定收入和额外收入；常填内容会记住，下次可以直接选。',example:['蔬菜 · 青菜','支出 ¥ 12.00']},
 {title:'借出去的钱，单独记',icon:Users,description:'打开底部“借款”，点“记借出”，填写姓名和金额。收到还款时，直接点列表中的“记还钱”。',hint:'可以分次还款，剩余欠款会自动更新；借出和还款不计入总收支。',example:['张师傅 · 未结清','记还钱']},
 {title:'查账，先选时间',icon:BookOpen,description:'“记账汇总”看收支和分类统计；“收支明细”按天查看，点击日期展开当天记录。',hint:'支持今日、本月、本年和自定义时间；明细和借款都可以搜索。',example:['10月31日 · 共 3 笔','点击展开']},
 {title:'备份好，再放心用',icon:Database,description:'在“设置 → 备份与恢复”导出完整 JSON 文件。换手机时，在新手机里恢复这份文件。',hint:'本机自动备份也在设置里开启。它仍保存在本机；卸载前请先导出文件。还可以设置隐私锁和字号。',example:['已有旧账本？','导出 JSON → 在这里恢复']},
];

export function FirstUseGuide({open,onFinish}:{open:boolean;onFinish:()=>void}){
 const [index,setIndex]=useState(0);
 useEffect(()=>{if(open)setIndex(0)},[open]);
 const step=steps[index],Icon=step.icon,last=index===steps.length-1;
 return <Dialog open={open} onOpenChange={value=>{if(!value)onFinish()}}>
  <DialogContent className="first-use-guide" showCloseButton={false}>
   <div className="guide-heading"><span>使用教程 · {index+1}/{steps.length}</span><button type="button" aria-label="关闭使用教程" onClick={onFinish}><X size={20}/></button></div>
   <div className="guide-progress" aria-label={`教程进度，第 ${index+1} 步，共 ${steps.length} 步`}>{steps.map((_,n)=><span key={n} className={n<=index?'is-complete':''}/>)}</div>
   <div className="guide-scroll" key={index} aria-live="polite" aria-atomic="true">
    <div className="guide-icon"><Icon size={28} aria-hidden="true"/></div>
    <DialogTitle>{step.title}</DialogTitle>
    <DialogDescription>{step.description}</DialogDescription>
    <div className="guide-example" aria-label="示例，不会保存到账本"><strong>{step.example[0]}</strong><span>{step.example[1]}</span></div>
    <p className="guide-hint">{step.hint}</p>
   </div>
   <div className="guide-actions"><button type="button" className="button secondary" onClick={()=>index?setIndex(index-1):onFinish()}>{index?<><ChevronLeft size={16}/>上一步</>:'稍后再看'}</button><button type="button" className="button primary" onClick={()=>last?onFinish():setIndex(index+1)}>{last?'开始使用':<>下一步<ChevronRight size={16}/></>}</button></div>
   <p className="guide-reopen">以后可在“设置 → 使用教程”重新查看</p>
  </DialogContent>
 </Dialog>;
}
