package com.trueket.backend.trueshot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.trueshot.dto.SecondMission;
import com.trueket.backend.trueshot.entity.*;
import com.trueket.backend.trueshot.repository.*;
import com.trueket.backend.trueshot.service.TrueshotExpiryJob;
import com.trueket.backend.trueshot.service.TrueshotRetentionService;
import com.trueket.backend.user.entity.User;
import com.trueket.backend.user.repository.UserRepository;
import com.trueket.backend.user.service.WithdrawalService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:trueshot-retention;MODE=PostgreSQL;DB_CLOSE_DELAY=-1")
class TrueshotRetentionTests {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final Set<String> OBJECTS=ConcurrentHashMap.newKeySet();
    private static final List<String> CALLS=new CopyOnWriteArrayList<>();
    private static final AtomicInteger FAIL_MASKED=new AtomicInteger();
    private static final AtomicInteger MISSING=new AtomicInteger();
    private static volatile String blockedKey;
    private static volatile CountDownLatch entered, release;
    private static final HttpServer STORAGE;
    static {
        try {
            STORAGE=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            STORAGE.createContext("/storage/v1/object/",exchange -> {
                String bucket=exchange.getRequestURI().getPath().substring("/storage/v1/object/".length());
                var body=JSON.readTree(exchange.getRequestBody());
                var keys=new ArrayList<String>();
                body.path("prefixes").forEach(key -> keys.add(key.asText()));
                int status=200;
                if (!"DELETE".equals(exchange.getRequestMethod())
                        || !"Bearer retention-test-key".equals(exchange.getRequestHeaders().getFirst("Authorization"))) status=401;
                for (String key:keys) CALLS.add(bucket+":"+key);
                if (keys.contains(blockedKey)) {
                    entered.countDown();
                    try { if (!release.await(10,TimeUnit.SECONDS)) status=504; }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); status=503; }
                }
                if (bucket.equals("masked-test") && FAIL_MASKED.getAndUpdate(value -> Math.max(0,value-1))>0) status=503;
                if (status==200) {
                    for (String key:keys) if (!OBJECTS.remove(bucket+":"+key)) MISSING.incrementAndGet();
                }
                byte[] response=(status==200?"[]":"{\"error\":\"temporary failure\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status,response.length);
                exchange.getResponseBody().write(response);
                exchange.close();
            });
            STORAGE.start();
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }

    @DynamicPropertySource static void storage(DynamicPropertyRegistry properties) {
        properties.add("storage.supabase-url",()->"http://127.0.0.1:"+STORAGE.getAddress().getPort());
        properties.add("storage.service-role-key",()->"retention-test-key");
        properties.add("storage.evidence-bucket",()->"evidence-test");
        properties.add("storage.trueshot-original-bucket",()->"original-test");
        properties.add("storage.trueshot-masked-bucket",()->"masked-test");
    }

    @Autowired TrueshotRetentionService retention;
    @Autowired TrueshotExpiryJob job;
    @Autowired TrueshotRequestRepository requests;
    @Autowired TrueshotPhotoRepository photos;
    @Autowired UserRepository users;
    @Autowired WithdrawalService withdrawal;

    @BeforeEach void reset() {
        photos.deleteAllInBatch(); requests.deleteAllInBatch(); users.deleteAllInBatch();
        OBJECTS.clear(); CALLS.clear(); FAIL_MASKED.set(0); MISSING.set(0);
        blockedKey=null; entered=new CountDownLatch(1); release=new CountDownLatch(1);
    }
    @AfterAll static void stop() { STORAGE.stop(0); }

    @Test void exactRetentionBoundaryDeletesBothKindsAndPreservesUnrelatedRequest() {
        // 고정한 기준 시각으로 경계 포함 여부를 검증한다. 실제 주기 작업보다 미래여서 자동 정리와 겹치지 않는다.
        var cutoff=now().plusDays(1).withNano(0);
        var expired=fixture(user(),cutoff.minusDays(7),true);
        var younger=fixture(user(),cutoff.minusDays(7).plusSeconds(1),true);
        assertFalse(retention.purge(younger.id(),cutoff));
        assertTrue(CALLS.isEmpty());
        assertTrue(retention.purge(expired.id(),cutoff));
        assertGone(expired);
        assertEquals(Set.copyOf(younger.objects()),Set.copyOf(OBJECTS));
        assertTrue(requests.existsById(younger.id()));
        assertTrue(users.existsById(expired.userId()));
        assertFalse(retention.purge(expired.id(),cutoff));
        assertEquals(4,CALLS.size());
    }

    @Test void partialStorageDeletionKeepsAllDatabaseKeysAndRetryAcceptsMissingObjects() {
        var cutoff=now().plusDays(1);
        var f=fixture(user(),cutoff.minusDays(8),true);
        FAIL_MASKED.set(1);
        assertThrows(ApiException.class,()->retention.purge(f.id(),cutoff));
        assertTrue(requests.existsById(f.id()));
        var stored=photos.findByRequestId(f.id());
        assertEquals(2,stored.size());
        assertTrue(stored.stream().allMatch(photo -> photo.getOriginalStorageKey()!=null && photo.getMaskedImage()!=null));
        assertEquals(2,OBJECTS.size());
        assertTrue(OBJECTS.stream().allMatch(key -> key.startsWith("masked-test:")));
        assertTrue(retention.purge(f.id(),cutoff));
        assertGone(f);
        assertTrue(OBJECTS.isEmpty());
        assertEquals(2,MISSING.get());
    }

    @Test void previouslyDeletedOriginalsAndRequestsWithoutPhotosAreHandled() {
        var cutoff=now().plusDays(1);
        var maskedOnly=fixture(user(),cutoff.minusDays(8),false);
        assertTrue(retention.purge(maskedOnly.id(),cutoff));
        assertGone(maskedOnly);
        assertEquals(2,CALLS.size());
        assertTrue(CALLS.stream().allMatch(key -> key.startsWith("masked-test:")));
        var noPhotos=emptyRequest(user(),cutoff.minusDays(8));
        assertTrue(retention.purge(noPhotos.getId(),cutoff));
        assertFalse(requests.existsById(noPhotos.getId()));
        assertEquals(2,CALLS.size());
    }

    @Test void jobDoesNotSkipRowsAcrossBatchBoundaryOrStopAfterOneFailure() {
        var owner=user();
        var failed=fixture(owner,now().minusDays(8),true);
        var ids=new ArrayList<Long>();
        for (int i=0;i<100;i++) ids.add(emptyRequest(owner,now().minusDays(8)).getId());
        var retained=fixture(user(),now(),true);
        FAIL_MASKED.set(1);
        job.purgeExpiredRequests();
        assertTrue(requests.existsById(failed.id()));
        assertTrue(ids.stream().noneMatch(requests::existsById));
        assertTrue(requests.existsById(retained.id()));
        job.purgeExpiredRequests();
        assertGone(failed);
        assertEquals(Set.copyOf(retained.objects()),Set.copyOf(OBJECTS));
    }

    @Test void concurrentPurgesDeleteOnceAndSecondExecutionIsHarmless() throws Exception {
        var cutoff=now().plusDays(1);
        var f=fixture(user(),cutoff.minusDays(8),true);
        blockedKey=f.objects().get(0).substring("original-test:".length());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->retention.purge(f.id(),cutoff));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var second=pool.submit(()->retention.purge(f.id(),cutoff));
            release.countDown();
            assertTrue(first.get(10,TimeUnit.SECONDS));
            assertFalse(second.get(10,TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
        assertGone(f);
        assertEquals(4,CALLS.size());
        assertEquals(0,MISSING.get());
    }

    @Test void withdrawalWaitsForPurgeAndStillRemovesRemainingOwnedData() throws Exception {
        var cutoff=now().plusDays(1);
        var owner=user();
        var f=fixture(owner,cutoff.minusDays(8),true);
        var otherOwned=fixture(owner,now(),true);
        var otherUser=fixture(user(),now(),true);
        blockedKey=f.objects().get(0).substring("original-test:".length());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var purge=pool.submit(()->retention.purge(f.id(),cutoff));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var withdraw=pool.submit(()->withdrawal.withdraw(owner.getId()));
            release.countDown();
            assertTrue(purge.get(10,TimeUnit.SECONDS));
            withdraw.get(10,TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
        assertGone(f); assertGone(otherOwned);
        assertFalse(users.existsById(owner.getId()));
        assertTrue(requests.existsById(otherUser.id()));
        assertEquals(Set.copyOf(otherUser.objects()),Set.copyOf(OBJECTS));
    }

    @Test void purgeAfterConcurrentWithdrawalBecomesNoOp() throws Exception {
        var cutoff=now().plusDays(1);
        var owner=user();
        var f=fixture(owner,cutoff.minusDays(8),true);
        blockedKey=f.objects().get(1).substring("masked-test:".length());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var withdraw=pool.submit(()->withdrawal.withdraw(owner.getId()));
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            var purge=pool.submit(()->retention.purge(f.id(),cutoff));
            release.countDown();
            withdraw.get(10,TimeUnit.SECONDS);
            assertFalse(purge.get(10,TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
        assertGone(f);
        assertTrue(OBJECTS.isEmpty());
        assertEquals(4,CALLS.size());
    }

    private Fixture fixture(User user,OffsetDateTime createdAt,boolean originals) {
        var request=TrueshotRequest.create(user,"보관 만료","AB12",UUID.randomUUID().toString(),createdAt);
        request.start(createdAt); request.firstPhotoAccepted(createdAt);
        request.revealSecondInstruction(SecondMission.of(SecondMission.Type.GESTURE,null),createdAt);
        request.secondPhotoReceived(createdAt);
        request.finishJudgment(true,true,List.of(),createdAt);
        request.finishSharing(true,createdAt);
        request=requests.save(request);
        var keys=new ArrayList<String>();
        for (short n=1;n<=2;n++) {
            var photo=TrueshotPhoto.create(request,n,createdAt);
            String original="trueshots/"+request.getId()+"/original-"+n+".jpg";
            String masked="trueshots/"+request.getId()+"/masked-"+n+".jpg";
            if (originals) { photo.storeOriginal(original); keys.add("original-test:"+original); }
            else photo.markOriginalDeleted(createdAt);
            photo.maskingReady(masked,JSON.createObjectNode()); keys.add("masked-test:"+masked);
            photos.save(photo);
        }
        OBJECTS.addAll(keys);
        return new Fixture(request.getId(),user.getId(),List.copyOf(keys));
    }
    private TrueshotRequest emptyRequest(User user,OffsetDateTime createdAt) {
        return requests.save(TrueshotRequest.create(user,"사진 없는 요청","AB12",UUID.randomUUID().toString(),createdAt));
    }
    private User user() {
        String unique=UUID.randomUUID().toString();
        return users.save(User.create(unique+"@example.com","unused",unique,"unused","1234",now()));
    }
    private void assertGone(Fixture f) {
        assertFalse(requests.existsById(f.id()));
        assertTrue(photos.findByRequestId(f.id()).isEmpty());
    }
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    private record Fixture(Long id,Long userId,List<String> objects) {}
}
