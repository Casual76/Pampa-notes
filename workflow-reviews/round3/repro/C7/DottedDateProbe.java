import dev.pampa.pampanotes.core.importing.*;
public class DottedDateProbe {
 public static void main(String[] args) {
   for (String name : new String[]{"2025.09.22", "2025.09.22.m4a", "2025.09.22_10.15"}) {
     System.out.println(name + " | day=" + RecordingDate.INSTANCE.fromFileName(name) + " | origin=" + RecordingOrigin.Companion.fromFileName(name));
   }
 }
}