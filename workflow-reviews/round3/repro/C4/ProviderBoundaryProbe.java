import java.util.*;import java.lang.reflect.*;import java.io.*;
import kotlin.Unit;import kotlin.coroutines.EmptyCoroutineContext;import kotlin.jvm.functions.Function1;import kotlinx.coroutines.BuildersKt;
import dev.pampa.pampanotes.core.db.*;import dev.pampa.pampanotes.core.files.*;import dev.pampa.pampanotes.core.archive.*;import dev.pampa.pampanotes.core.transcription.*;import dev.pampa.pampanotes.core.alignment.*;
public class ProviderBoundaryProbe {
 static Object call(Object target,String name,Class<?>[] types,Object...args)throws Exception{var m=target.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(target,args);}
 static String quote(String text){return "\""+text.replace("\\","\\\\").replace("\"","\\\"").replace("\t","\\t").replace("\r","\\r").replace("\n","\\n")+"\"";}
 public static void main(String[] args)throws Exception{
  int index=0, failures=0;for(String exact:new String[]{"  derived,  segment! \t\r\n"," \t\r\n",null}){
   var test=new CloudProvenanceRecoveryTest();test.getTemp().create();
   try{
    var files=(AppFiles)call(test,"files",new Class<?>[]{});var a=(AudioPartEntity)call(test,"part",new Class<?>[]{AppFiles.class,String.class,int.class},files,"a",0);
    String body="{\"language\":\"Italian\","+(exact==null?"":"\"text\":"+quote(exact)+",")+"\"segments\":[{\"start\":0,\"end\":1,\"text\":\"  derived,  segment! \"}]}";
    var parsed=OpenRouterTranscriptionProvider.Companion.parseResponse(AlignmentProtocol.INSTANCE.getJson().parseToJsonElement(body));
    var provider=(TranscriptionProvider)call(test,"provider",new Class<?>[]{String.class,boolean.class,Function1.class},"openrouter",false,(Function1<File,TranscriptResult>)(f->parsed));
    var field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);var unsafe=(sun.misc.Unsafe)field.get(null);var fetcher=(ArchiveFetcher)unsafe.allocateInstance(ArchiveFetcher.class);
    var runner=new TranscriptionRunner(files,fetcher);var request=new TranscribeRequest("pinned","it",null,0,false,null,null,null,null,0,null,null,null);
    SessionTranscript result=BuildersKt.<SessionTranscript>runBlocking(EmptyCoroutineContext.INSTANCE,(scope,cont)->runner.transcribeSession("probe-"+a.getId(),List.of(a),provider,request,10,false,(id,at,c)->Unit.INSTANCE,x->Unit.INSTANCE,x->Unit.INSTANCE,cont));
    var saved=(kotlin.Pair<?,?>)call(test,"save",new Class<?>[]{SessionTranscript.class,List.class,List.class},result,List.of(a),List.of(a));var doc=(CloudSourceDocument)saved.getSecond();
    System.out.println("CASE "+index+++" supplied="+(exact==null?"ABSENT":quote(exact))+" parsed="+quote(parsed.getText())+" cachedSource="+quote(result.getSourceParts().get(0).getText())+" exactKnown="+result.getSourceParts().get(0).getExactTextKnown()+" saved="+quote((String)saved.getFirst())+" scope="+doc.getOriginals().get(0).getContentScope()+" binds="+(CloudSources.INSTANCE.bindSource(doc,List.of(a))!=null));
    if(exact!=null&&!exact.equals(saved.getFirst())){failures++;System.out.println("FAIL exact response whitespace replaced by segments");}
    if(exact==null&&(result.getSourceParts().get(0).getExactTextKnown()||doc.getOriginals().get(0).getContentScope().equals("complete_original"))){failures++;System.out.println("FAIL absent response text certified as exact complete original");}
    java.nio.file.Files.writeString(java.nio.file.Path.of(args[0],"provider-case-"+(index-1)+".json"),CloudSources.INSTANCE.encode(doc));
    runner.cleanUp("probe-"+a.getId());
   }finally{test.getTemp().delete();}
  }
  System.out.println("PROVIDER BOUNDARY REQUIREMENT FAILURES="+failures);if(failures!=0)System.exit(2);
 }
}
