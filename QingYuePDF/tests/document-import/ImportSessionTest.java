package cn.qingyue.pdf;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ImportSessionTest {
    static int checks;

    public static void main(String[] args) throws Exception {
        Owner owner=new Owner();
        ImportSession session=new ImportSession("one",Runnable::run,owner);
        List<ImportSession.Phase> phases=new ArrayList<>();
        ImportSession.Listener listener=(source,snapshot)->phases.add(snapshot.phase);
        session.attach(listener);
        check(phases.size()==1&&phases.get(0)==ImportSession.Phase.QUEUED,"attach replays state");
        check(session.progress(ImportSession.Phase.COPYING,ImportSession.Unit.BYTES,50,100,"copy"),"progress accepted");
        check(session.snapshot().percent()==50,"determinate percent");
        session.progress(ImportSession.Phase.COPYING,ImportSession.Unit.BYTES,25,80,"stale copy");
        check(session.snapshot().completed==50&&session.snapshot().total==100,"same-phase progress is monotonic");
        ImportSession.Result result=result("one");
        check(session.ready(result),"result ready once");
        check(!session.ready(result),"duplicate completion ignored");
        ImportSession.Claim claim=session.claim();
        check(claim!=null&&claim.result==result,"first claimant owns result");
        check(session.claim()==null,"second claimant blocked");
        check(claim.complete("文档已存在"),"claim commit");
        check(claim.complete(),"claim commit idempotent");
        check(owner.consumed==1&&session.phase()==ImportSession.Phase.COMPLETED,"single consume callback");
        check(session.snapshot().completionMessage.equals("文档已存在"),"completion detail survives terminal state");
        List<String> terminalReplay=new ArrayList<>();
        session.attach((source,snapshot)->terminalReplay.add(snapshot.completionMessage));
        check(terminalReplay.size()==1&&terminalReplay.get(0).equals("文档已存在"),"completed state replays after recreation");
        session.detach(listener);
        int detached=phases.size();
        session.progress(ImportSession.Phase.COPYING,ImportSession.Unit.BYTES,1,2,"ignored");
        check(phases.size()==detached,"detached listener stays detached");

        Owner retryOwner=new Owner();
        ImportSession retry=new ImportSession("two",Runnable::run,retryOwner);
        retry.ready(result("two"));
        ImportSession.Claim first=retry.claim();
        check(first!=null&&first.release(),"failed library commit releases claim");
        ImportSession.Claim second=retry.claim();
        check(second!=null&&second!=first,"released result can be reclaimed");
        check(second.discard(),"claimed result can be discarded");
        check(retryOwner.discarded==1&&retry.phase()==ImportSession.Phase.CANCELED,"discard cleanup callback");

        Owner failureOwner=new Owner();
        ImportSession failure=new ImportSession("failure",Runnable::run,failureOwner);
        failure.ready(result("failure"));
        ImportSession.Claim failedClaim=failure.claim();
        check(failedClaim!=null&&failedClaim.fail("资料库索引写入失败"),"claim publishes commit failure");
        check(failedClaim.fail("ignored")&&failure.phase()==ImportSession.Phase.FAILED,"claim failure is idempotent");
        check(failure.snapshot().error.equals("资料库索引写入失败")&&!failure.snapshot().resultAvailable,"failure detail is durable");
        check(failureOwner.discarded==1,"failed claim cleans staging once");

        Owner cancelOwner=new Owner();
        ImportSession cancel=new ImportSession("three",Runnable::run,cancelOwner);
        check(cancel.cancel(),"running task cancellation");
        check(!cancel.cancel(),"cancellation idempotent");
        check(cancelOwner.canceled==1&&cancel.phase()==ImportSession.Phase.CANCELED,"single cancel callback");
        check(!cancel.progress(ImportSession.Phase.CONVERTING,ImportSession.Unit.PAGES,1,-1,"late"),"late progress ignored");

        Owner readyCancelOwner=new Owner();
        ImportSession readyCancel=new ImportSession("ready-cancel",Runnable::run,readyCancelOwner);
        readyCancel.ready(result("ready-cancel"));
        check(readyCancel.cancel(),"READY result can be canceled before the UI claims it");
        check(readyCancel.phase()==ImportSession.Phase.CANCELED&&readyCancel.claim()==null,
                "READY cancellation cannot later commit the staged result");
        check(readyCancelOwner.discarded==1&&readyCancelOwner.canceled==0,
                "READY cancellation discards staging exactly once");

        Owner replayOwner=new Owner();
        List<Runnable> posted=new ArrayList<>();
        ImportSession replayRace=new ImportSession("replay-race",posted::add,replayOwner);
        List<ImportSession.Phase> delivered=new ArrayList<>();
        replayRace.attach((source,snapshot)->delivered.add(snapshot.phase));
        replayRace.progress(ImportSession.Phase.COPYING,ImportSession.Unit.BYTES,1,2,"copy");
        check(posted.size()==2,"attach replay and progress are both queued");
        posted.get(1).run();
        posted.get(0).run();
        check(delivered.size()==1&&delivered.get(0)==ImportSession.Phase.COPYING,
                "late stale replay cannot roll the listener state backwards");

        Owner raceOwner=new Owner();
        ImportSession race=new ImportSession("race",Runnable::run,raceOwner);
        race.ready(result("race"));
        CountDownLatch start=new CountDownLatch(1);
        AtomicInteger winners=new AtomicInteger();
        AtomicReference<ImportSession.Claim> winningClaim=new AtomicReference<>();
        Thread[] contenders=new Thread[8];
        for(int i=0;i<contenders.length;i++){
            contenders[i]=new Thread(()->{try{start.await();ImportSession.Claim won=race.claim();if(won!=null){winners.incrementAndGet();winningClaim.set(won);}}catch(InterruptedException error){Thread.currentThread().interrupt();}},"claim-"+i);
            contenders[i].start();
        }
        start.countDown();for(Thread contender:contenders)contender.join();
        check(winners.get()==1,"concurrent claim has one winner");
        check(winningClaim.get()!=null&&winningClaim.get().discard(),"concurrent winning claim remains usable");

        System.out.println("ImportSessionTest: "+checks+" checks passed; replay, progress, claim, retry and cancellation");
    }

    static ImportSession.Result result(String id){File dir=new File("staging-"+id);return new ImportSession.Result(id,"name","hash",2,10,"PDF",new File(dir,"document.pdf"),null,"",dir);}
    static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}

    static final class Owner implements ImportSession.Owner {
        int canceled,consumed,discarded;
        public void cancel(ImportSession session){canceled++;}
        public void consumed(ImportSession session){consumed++;}
        public void discarded(ImportSession session){discarded++;}
    }
}
