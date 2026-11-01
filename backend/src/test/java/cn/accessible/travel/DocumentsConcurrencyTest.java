package cn.accessible.travel;

import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.profiles.active=dev","spring.datasource.url=jdbc:h2:mem:documents-concurrency;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","spring.datasource.username=sa","spring.datasource.password=","app.admin-username=documents-concurrency-admin","app.admin-password=DocumentsConcurrency123!"})
class DocumentsConcurrencyTest {
    @Autowired DocumentsService documents;
    @Autowired Db db;

    @Test void simultaneousEditorsCannotOverwriteEachOtherAndPublishRetryIsAtomic()throws Exception {
        long actor=((Number)db.one("SELECT id FROM users WHERE role='ADMIN' ORDER BY id LIMIT 1").get("id")).longValue();
        var initial=documents.get("service");
        var original=initial.draft();
        var first=new DocumentsService.Document("编辑甲",original.introduction(),original.footerTitle(),original.footer(),original.sections());
        var second=new DocumentsService.Document("编辑乙",original.introduction(),original.footerTitle(),original.footer(),original.sections());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var saveGate=new CyclicBarrier(2);
            List<Callable<Integer>> writes=List.of(
                ()->saveAfterGate(saveGate,initial.revision(),first,actor),
                ()->saveAfterGate(saveGate,initial.revision(),second,actor));
            var saves=pool.invokeAll(writes);
            assertThat(List.of(saves.get(0).get(15,TimeUnit.SECONDS),saves.get(1).get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            var saved=documents.get("service");
            assertThat(saved.revision()).isEqualTo(initial.revision()+1);
            assertThat(saved.draft().title()).isIn("编辑甲","编辑乙");
            assertThat(saved.published()).isEqualTo(initial.published());
            assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action='DOCUMENT_DRAFT_SAVE'")).isEqualTo(1);

            var publishGate=new CyclicBarrier(2);
            Callable<DocumentsService.State> publish=()->{
                publishGate.await(10,TimeUnit.SECONDS);
                return documents.publish("service",new DocumentsService.PublishRequest(saved.revision()),actor);
            };
            var publishes=pool.invokeAll(List.of(publish,publish));
            var published=publishes.get(0).get(15,TimeUnit.SECONDS);
            var retried=publishes.get(1).get(15,TimeUnit.SECONDS);
            assertThat(published.publishedRevision()).isEqualTo(saved.revision());
            assertThat(published.published()).isEqualTo(saved.draft());
            assertThat(retried.publishedAt()).isEqualTo(published.publishedAt());
            assertThat(db.count("SELECT COUNT(*) FROM audit_logs WHERE action='DOCUMENT_PUBLISH'")).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    private int saveAfterGate(CyclicBarrier gate,long revision,DocumentsService.Document document,long actor)throws Exception {
        gate.await(10,TimeUnit.SECONDS);
        try {documents.save("service",new DocumentsService.SaveRequest(revision,document),actor);return 200;}
        catch(Api.Failure failure){return failure.status;}
    }
}
