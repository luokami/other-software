import {repaid,type Entry,type Loan} from './book.ts';
const normalize=(value:string)=>value.normalize('NFKC').toLocaleLowerCase().replace(/[\s,，¥￥]/g,'');
function matches(fields:string[],query:string){const terms=query.normalize('NFKC').trim().split(/\s+/).map(normalize).filter(Boolean);const source=normalize(fields.join(' '));return terms.every(term=>source.includes(term));}
export function matchesEntry(entry:Entry,query:string){
 if(entry.deletedAt)return false;
 return matches([entry.category,entry.category==='水电开销'?'生活缴费':'',entry.type==='income'?(entry.incomeKind==='extra'?'额外收入':'固定收入'):'支出',entry.item||'',entry.note,entry.date,(entry.cents/100).toFixed(2)],query);
}
export function matchesLoan(loan:Loan,query:string){
 if(loan.deletedAt)return false;
 const paid=repaid(loan);
 return matches([loan.name,loan.note,loan.date,(loan.cents/100).toFixed(2),(paid/100).toFixed(2),((loan.cents-paid)/100).toFixed(2),paid===loan.cents?'已结清':'未结清',...loan.payments.filter(p=>!p.deletedAt).map(p=>`${p.date} ${(p.cents/100).toFixed(2)}`)],query);
}
