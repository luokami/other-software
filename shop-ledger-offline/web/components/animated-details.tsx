'use client';

import {useEffect,useRef,type MouseEvent,type ReactNode} from 'react';

// Retain native summary keyboard behavior while animating both directions.
export function AnimatedDetails({className,children}:{className:string;children:ReactNode}){
 const animationRef=useRef<Animation|null>(null);
 const targetOpenRef=useRef(false);
 useEffect(()=>()=>animationRef.current?.cancel(),[]);

 function toggle(event:MouseEvent<HTMLDetailsElement>){
  const details=event.currentTarget;
  const summary=(event.target as Element).closest('summary');
  if(event.defaultPrevented||summary?.parentElement!==details)return;
  event.preventDefault();
  const opening=animationRef.current?!targetOpenRef.current:!details.open;
  targetOpenRef.current=opening;
  const startHeight=details.getBoundingClientRect().height;
  animationRef.current?.cancel();
  animationRef.current=null;
  details.style.height='';
  details.style.overflow='';
  delete details.dataset.expanded;

  if(window.matchMedia('(prefers-reduced-motion: reduce)').matches||!details.animate){
   details.open=opening;
   return;
  }

  details.open=opening;
  const endHeight=details.getBoundingClientRect().height;
  // Keep the content visible until a closing animation finishes.
  details.open=true;
  details.style.height=`${startHeight}px`;
  details.style.overflow='hidden';
  details.dataset.expanded=String(opening);
  const animation=details.animate(
   [{height:`${startHeight}px`},{height:`${endHeight}px`}],
   {duration:180,easing:'cubic-bezier(.2,.7,.2,1)',fill:'forwards'}
  );
  animationRef.current=animation;
  animation.onfinish=()=>{
   if(animationRef.current!==animation)return;
   details.open=opening;
   details.style.height='';
   details.style.overflow='';
   delete details.dataset.expanded;
   animation.cancel();
   animationRef.current=null;
  };
 }

 return <details className={className} onClick={toggle}>{children}</details>;
}
