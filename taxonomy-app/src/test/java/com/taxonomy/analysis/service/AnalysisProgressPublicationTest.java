package com.taxonomy.analysis.service;
import com.taxonomy.workspace.service.WorkspaceContext;
import org.springframework.core.env.StandardEnvironment;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.atomic.*;
class AnalysisProgressPublicationTest {
 @org.junit.jupiter.api.Test void terminalPublicationAlwaysIncludesTheRetentionTimestamp() throws Exception {
  var registry=new AnalysisProgressRegistry(new StandardEnvironment());
  var latest=new AtomicReference<Object>(); var done=new AtomicBoolean(); var failures=new AtomicInteger();
  var field=AnalysisProgressRegistry.Handle.class.getDeclaredField("run");field.setAccessible(true);
  try(var initial=registry.open(null,"owner",WorkspaceContext.SHARED,null)) {latest.set(field.get(initial)); initial.finish("SUCCESS");}
  var type=latest.get().getClass();var lookup=MethodHandles.privateLookupIn(type,MethodHandles.lookup());
  var status=lookup.findVarHandle(type,"status",String.class);var finished=lookup.findVarHandle(type,"finishedAt",long.class);
  Thread reader=Thread.ofPlatform().start(()->{while(!done.get()) {var run=latest.get();var value=(String)status.getVolatile(run);if(!"RUNNING".equals(value)&&!"CANCELLING".equals(value)&&(long)finished.getVolatile(run)==0) failures.incrementAndGet();}});
  try {for(int i=0;i<20000;i++) {try(var h=registry.open(null,"owner",WorkspaceContext.SHARED,null)){latest.set(field.get(h));h.finish("SUCCESS");}}}
  finally {done.set(true);reader.join();}
  if(failures.get()!=0) throw new AssertionError("Terminal publication race reproduced");
 }
}
