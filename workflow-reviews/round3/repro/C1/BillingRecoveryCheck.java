import dev.pampa.pampanotes.core.spending.*;
import dev.pampa.pampanotes.core.testing.CloudHttpFixture;
import dev.pampa.pampanotes.core.transcription.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import kotlin.Unit;
import static org.junit.Assert.*;

/** New counterexample at the production accounting/HTTP boundary, with mocked Android DAOs. */
public final class BillingRecoveryCheck {
  static CloudSpending reopen(CloudDirectRefreshTest owner,CloudHttpFixture f) throws Exception {
    Method m=CloudDirectRefreshTest.class.getDeclaredMethod("spending",CloudHttpFixture.class);m.setAccessible(true);return (CloudSpending)m.invoke(owner,f);
  }
  static TranscribeRequest request(){return new TranscribeRequest("openai/whisper-1","en",null,0,false,null,"session",null,"same-job",60_000,null,null,null);}
  static void response(CloudHttpFixture f,int code,String body){f.setOnRequest(url->{f.setCode(code);f.setBody(body);return Unit.INSTANCE;});}
  static CloudTranscriptionGuard guard(CloudHttpFixture f,CloudSpending spending) {
    return new CloudTranscriptionGuard(new OpenRouterTranscriptionProvider(f.http(),f.getKey(),OpenRouterTranscriptionProvider.BASE_URL),
      (r,c)->spending.beforeUpload("openrouter",OpenRouterCostMetadata.Companion.fingerprint(f.getKey()),r,false,c),
      (r,c)->r,c->Unit.INSTANCE,(e,c)->e,c->false);
  }
  static Object send(CloudTranscriptionGuard guard,File audio) throws Throwable {
    return OfflineMistralChecks.await(c->guard.transcribe(audio,"audio/wav",request(),x->Unit.INSTANCE,x->Unit.INSTANCE,OfflineMistralChecks.cast(c)));
  }
  public static void main(String[] args) throws Throwable {
    for(int status:new int[]{401,402})for(boolean paidFirst:new boolean[]{false,true}) {
      CloudDirectRefreshTest owner=new CloudDirectRefreshTest();owner.getTemp().create();
      try {
        CloudHttpFixture f=new CloudHttpFixture(new File(owner.getTemp().getRoot(),"quota.json"));
        Method respond=CloudDirectRefreshTest.class.getDeclaredMethod("respond",CloudHttpFixture.class,String.class);respond.setAccessible(true);respond.invoke(owner,f,null);
        CloudSpending spending=reopen(owner,f);
        OfflineMistralChecks.await(c->spending.refresh(null,OfflineMistralChecks.cast(c)));
        CloudCostSettings settings=new CloudCostSettings("0.09",BudgetPeriod.MONTHLY,"UTC",null,UnknownCostAction.ALLOW,Collections.emptyMap(),0);
        OfflineMistralChecks.await(c->spending.configure(settings,OfflineMistralChecks.cast(c)));
        File audio=new File(owner.getTemp().getRoot(),"audio.fixture");audio.createNewFile();
        if(paidFirst) {
          response(f,200,"{\"text\":\"Paid A\",\"usage\":{\"cost\":0.01}}");
          send(guard(f,spending),audio);
        }
        response(f,status,"{\"error\":{\"message\":\"definitive rejection\"}}");
        Throwable observed=null;
        try {send(guard(f,spending),audio);}catch(Throwable t){observed=t;}
        assertEquals(status==401?"unauthorized":"credit_required",TranscriptionError.Companion.from(observed).getCode());
        int before=f.getRequests().size();
        if(status==401)f.setKey("fixed-key");response(f,200,"{\"text\":\"B after fix\",\"usage\":{\"cost\":0.01}}");
        CloudSpending fresh=reopen(owner,f);Throwable retryError=null;
        try {send(guard(f,fresh),audio);}catch(Throwable t){retryError=t;}
        assertTrue(retryError instanceof TranscriptionError.CloudControl);assertEquals("cloud_budget_exceeded",((TranscriptionError)retryError).getCode());assertEquals(before,f.getRequests().size());
        CloudCostData data=(CloudCostData)OfflineMistralChecks.await(c->fresh.data(OfflineMistralChecks.cast(c)));
        assertNull(data.getCharges().get(data.getCharges().size()-1).getActualUsd());assertEquals("0.060000",data.getCharges().get(data.getCharges().size()-1).getReservedUsd());
        if(paidFirst)assertEquals("0.01",data.getCharges().get(0).getActualUsd());
        System.out.println("REPRODUCED status="+status+" paidFirst="+paidFirst+" exact HTTP rejection leaves reserved USD 0.060000; corrected key/credit + reopened spending -> cloud_budget_exceeded; no retry HTTP");
        CloudCharge rejected=data.getCharges().get(data.getCharges().size()-1);
        OfflineMistralChecks.await(c->fresh.reconcile(rejected.getId(),"0",OfflineMistralChecks.cast(c)));
        send(guard(f,fresh),audio);assertEquals(before+1,f.getRequests().size());
        System.out.println("CONTROL status="+status+" paidFirst="+paidFirst+" explicit reconciliation of rejected attempt to zero unblocks same job; prior confirmed A remains intact");
      }finally{owner.getTemp().delete();}
    }
  }
}
