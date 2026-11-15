package com.trueket.backend.trueshot.service;

import com.trueket.backend.global.storage.StorageArea;
import com.trueket.backend.global.storage.StorageTarget;
import com.trueket.backend.global.storage.SupabaseStorageDeletion;
import com.trueket.backend.trueshot.repository.TrueshotPhotoRepository;
import com.trueket.backend.trueshot.repository.TrueshotRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.OffsetDateTime;
import java.util.ArrayList;

@Service
public class TrueshotRetentionService {
    private final TrueshotRequestRepository requests;
    private final TrueshotPhotoRepository photos;
    private final SupabaseStorageDeletion storage;

    public TrueshotRetentionService(TrueshotRequestRepository requests, TrueshotPhotoRepository photos,
            SupabaseStorageDeletion storage) {
        this.requests=requests;
        this.photos=photos;
        this.storage=storage;
    }

    // 요청마다 별도 트랜잭션으로 처리해 한 건의 실패가 다른 요청 삭제를 취소하지 않도록 한다.
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean purge(Long requestId, OffsetDateTime cutoff) {
        // 탈퇴·판정·공유 처리와 같은 순서로 요청을 먼저 잠그고 만료 여부를 재검사한다.
        var request=requests.findByIdForUpdate(requestId).orElse(null);
        if (request==null || request.getPurgeAt().isAfter(cutoff)) return false;
        var stored=photos.findByRequestIdForUpdate(requestId);
        var targets=new ArrayList<StorageTarget>();
        for (var photo:stored) {
            if (photo.getOriginalStorageKey()!=null) {
                targets.add(new StorageTarget(StorageArea.TRUESHOT_ORIGINAL,photo.getOriginalStorageKey()));
            }
            if (photo.getMaskedImage()!=null) {
                targets.add(new StorageTarget(StorageArea.TRUESHOT_MASKED,photo.getMaskedImage()));
            }
        }
        // 일부 파일이 이미 삭제됐어도 재시도할 수 있다. 실패 시 DB와 모든 키를 보존한다.
        if (!targets.isEmpty()) storage.deleteAll(targets);
        photos.deleteAll(stored);
        photos.flush();
        requests.delete(request);
        requests.flush();
        return true;
    }
}
