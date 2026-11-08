import java.io.File;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import kotlin.coroutines.*;
import kotlinx.coroutines.BuildersKt;
import dev.pampa.pampanotes.core.repo.*;
import dev.pampa.pampanotes.core.files.*;
import dev.pampa.pampanotes.core.db.SizeTotal;

/** Tiny independent Java harness against the current archived compiled Kotlin classes.
 * Reuses only the checked-in test's DAO/policy/remote mock setup; all bytes are synthetic. */
public class C6BoundaryAdversary {
  static int retentionFailures=0;
  static Object call(Object f,String n,Class<?>[] ts,Object... args) {
    try { Method m=f.getClass().getDeclaredMethod(n,ts);m.setAccessible(true);return m.invoke(f,args); }
    catch(Exception e){throw new RuntimeException(e);}
  }
  static Object get(Object f,String n){return call(f,n,new Class<?>[0]);}
  static Object fixture(boolean mode) throws Exception {
    StorageArchiveIdentityTest t=new StorageArchiveIdentityTest();t.getTemp().create();
    Class<?> cl=Class.forName("dev.pampa.pampanotes.core.repo.StorageArchiveIdentityTest$Fixture");
    Constructor<?> co=cl.getDeclaredConstructors()[0];co.setAccessible(true);return co.newInstance(t,mode);
  }
  static File audio(Object f,String id,String name,long at) {
    File p=(File)call(f,"audio",new Class[]{String.class,String.class,long.class,String.class},id,name,at,"h");
    return p;
  }
  static File source(Object f,String id,String name,String derived,long at) {
    return (File)call(f,"source",new Class[]{String.class,String.class,String.class,long.class,String.class},id,name,derived,at,"h");
  }
  static void bytes(File p)throws Exception{p.getParentFile().mkdirs();Files.writeString(p.toPath(),"synthetic bytes");}
  static SizeTotal evict(Object f)throws Exception{
    return BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE,(scope,cont)->call(f,"evict",new Class[]{Continuation.class},cont));
  }
  static StorageRepository storage(Object f){return (StorageRepository)get(f,"getStorage");}
  static void snapshotRace(boolean mode, boolean source)throws Exception{
    Object f=fixture(mode);File p=source?source(f,"personal","./shared.pdf",null,1):audio(f,"personal","./shared.wav",1);bytes(p);
    AtomicInteger reads=new AtomicInteger();
    storage(f).setProtectedSessionIds(cont->{
      if(reads.incrementAndGet()==2) {
        // A writer commits while the final suspendable guard read is in flight: earlier DAO snapshots are stale.
        new Thread(() -> {
          if(source) source(f,"new-unarchived","shared.pdf",null,0);else audio(f,"new-unarchived","shared.wav",0);
          cont.resumeWith(Set.of());
        }, "C6-offline-row-writer").start();
        return kotlin.coroutines.intrinsics.IntrinsicsKt.getCOROUTINE_SUSPENDED();
      }
      return Set.of();
    });
    SizeTotal gone=evict(f);
    int rows=((List<?>)get(f,source?"getDocs":"getParts")).size();
    if(!p.exists())retentionFailures++;
    System.out.println("FINAL_SNAPSHOT_RACE mode="+(mode?"computer-only":"manual-archive")+" kind="+(source?"source":"audio")+" rows="+rows+" guardReads="+reads+" exists="+p.exists()+" gone="+gone+" EXPECT_RETAIN="+p.exists());
  }
  static void headChange(boolean mode)throws Exception{
    Object f=fixture(mode);File p=audio(f,"personal","./shared.wav",1);bytes(p);
    call(f,"setDuringConfirmation",new Class[]{kotlin.jvm.functions.Function2.class},(kotlin.jvm.functions.Function2<Object,Continuation<? super kotlin.Unit>,Object>)(it,c)->{
      audio(f,"new-unarchived","shared.wav",0);return kotlin.Unit.INSTANCE;
    });
    SizeTotal gone=evict(f);System.out.println("HEAD_ROW_CHANGE mode="+mode+" exists="+p.exists()+" gone="+gone);
    if(!p.exists()||gone.getCount()!=0) throw new AssertionError("HEAD row change lost bytes");
  }
  static void redirect(boolean mode)throws Exception{
    Object f=fixture(mode);AppFiles fs=(AppFiles)get(f,"getFiles");
    File real=Files.createTempDirectory(fs.getRoot().toPath().getParent(),"redirect-target-").toFile();
    // Windows junction creation is done by the caller; this Java method tests a portable symlink if permitted.
    if(!fs.getAudio().delete())throw new AssertionError("nonempty fixture audio");
    try{Files.createSymbolicLink(fs.getAudio().toPath(),real.toPath());}
    catch(Exception e){System.out.println("SYMLINK_GATE "+e.getClass().getSimpleName()+" "+e.getMessage());return;}
    File p=audio(f,"personal","shared.wav",1);bytes(p);SizeTotal gone=evict(f);
    System.out.println("REDIRECT mode="+mode+" canonical="+p.getCanonicalPath()+" exists="+p.exists()+" gone="+gone);
    if(!p.exists()||gone.getCount()!=0)throw new AssertionError("redirect lost bytes");
  }
  static void simulatedRootChange(boolean mode, boolean source, boolean duringHead)throws Exception{
    Object f=fixture(mode);AppFiles fs=(AppFiles)get(f,"getFiles");
    File p=source?source(f,"personal","shared.pdf",null,1):audio(f,"personal","shared.wav",1);bytes(p);
    File redirected=Files.createTempDirectory(fs.getRoot().toPath().getParent(),"redirected-root-").toFile();
    File other=new File(redirected,p.getName());bytes(other);
    Runnable change=()->{
      try {Field field=AppFiles.class.getDeclaredField(source?"sources":"audio");field.setAccessible(true);field.set(fs,redirected);}
      catch(Exception e){throw new RuntimeException(e);}
    };
    if(duringHead)call(f,"setDuringConfirmation",new Class[]{kotlin.jvm.functions.Function2.class},(kotlin.jvm.functions.Function2<Object,Continuation<? super kotlin.Unit>,Object>)(it,c)->{change.run();return kotlin.Unit.INSTANCE;});else change.run();
    SizeTotal gone=evict(f);
    System.out.println("ROOT_SUBSTITUTION mode="+mode+" source="+source+" duringHead="+duringHead+" original="+p.exists()+" outside="+other.exists()+" gone="+gone);
    if(!p.exists()||!other.exists()||gone.getCount()!=0)throw new AssertionError("root identity gate failed");
  }
  public static void main(String[]args)throws Exception{
    System.out.println("ACTUAL_CORE_CLASS="+StorageRepository.class.getProtectionDomain().getCodeSource().getLocation());
    System.out.println("ACTUAL_TEST_CLASS="+StorageArchiveIdentityTest.class.getProtectionDomain().getCodeSource().getLocation());
    for(boolean mode:new boolean[]{true,false}){headChange(mode);snapshotRace(mode,false);snapshotRace(mode,true);for(boolean source:new boolean[]{false,true})for(boolean duringHead:new boolean[]{false,true})simulatedRootChange(mode,source,duringHead);}
    if(retentionFailures!=0)throw new AssertionError(retentionFailures+" mandatory shared-byte retention checks failed");
  }
}
