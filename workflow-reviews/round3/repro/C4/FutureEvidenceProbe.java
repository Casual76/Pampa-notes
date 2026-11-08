import java.lang.reflect.*;import java.nio.file.*;
import dev.pampa.pampanotes.core.alignment.*;import dev.pampa.pampanotes.core.db.*;
public class FutureEvidenceProbe {
 static Object call(Object t,String n,Class<?>[] types,Object...args)throws Exception{var m=t.getClass().getDeclaredMethod(n,types);m.setAccessible(true);return m.invoke(t,args);}
 public static void main(String[] args)throws Exception{
  var f=new AlignmentLanguageApplicationRegressionTest();var raw=(TranscriptEntity)call(f,"source",new Class<?>[]{String.class},"Italian");
  String valid=raw.getCloudSourceJson();String unknown=valid.substring(0,valid.length()-1)+",\"futureEvidence\":\"IMMUTABLE_PUBLIC_CONTENT\"}";
  var extra=raw.copy(raw.getId(),raw.getSessionId(),raw.getKind(),raw.getProvider(),raw.getModel(),raw.getLanguage(),raw.getText(),raw.getPreset(),raw.getPromptHash(),raw.getParentId(),raw.getWordCount(),raw.getStatus(),raw.getCreatedAt(),unknown);
  var snapshot=(AlignmentSnapshot)call(f,"snapshot",new Class<?>[]{TranscriptEntity.class,String.class},extra,"it");var response=(AlignmentResponse)call(f,"response",new Class<?>[]{AlignmentSnapshot.class,String.class},snapshot,"it");
  var applied=AlignmentApplication.INSTANCE.plan(snapshot,snapshot,extra,response,"owner",false,"fresh-job-42");
  System.out.println("input decodes="+(CloudSources.INSTANCE.decode(extra.getCloudSourceJson())!=null)+" alignment writes="+(applied!=null)+" unknown evidence retained="+(applied!=null&&applied.getCloudSourceJson().contains("IMMUTABLE_PUBLIC_CONTENT")));
  Files.writeString(Path.of(args[0],"future-evidence-before.json"),unknown);if(applied!=null)Files.writeString(Path.of(args[0],"future-evidence-after.json"),applied.getCloudSourceJson());
  if(applied!=null&&!applied.getCloudSourceJson().contains("IMMUTABLE_PUBLIC_CONTENT")){System.out.println("FAIL unsupported nonempty source metadata silently rewritten, future public evidence stripped");System.exit(2);}
 }
}
