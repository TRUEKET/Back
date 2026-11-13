package com.trueket.backend.user.repository;

import com.trueket.backend.global.exception.ApiException;
import com.trueket.backend.global.storage.StorageArea;
import com.trueket.backend.global.storage.StorageTarget;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.ArrayList;
import java.util.List;

@Repository
public class WithdrawalRepository {
    private final JdbcTemplate jdbc;
    public WithdrawalRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public List<StorageTarget> lockAndCollect(Long userId) {
        // Lock parent rows too: FK inserts of new children wait until withdrawal commits.
        jdbc.queryForList("select id from reports where user_id = ? for update", Long.class,userId);
        jdbc.queryForList("select id from trueshot_requests where user_id = ? for update",Long.class,userId);
        List<StorageTarget> targets = new ArrayList<>();
        jdbc.query("select storage_key from evidence where report_id in (select id from reports where user_id = ?) for update",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> add(targets,StorageArea.EVIDENCE,row.getString(1)),userId);
        jdbc.query("select masked_image, original_storage_key from trueshot_photos where request_id in (select id from trueshot_requests where user_id = ?) for update",
                (org.springframework.jdbc.core.RowCallbackHandler) row -> {
                    add(targets,StorageArea.TRUESHOT_MASKED,row.getString(1));
                    add(targets,StorageArea.TRUESHOT_ORIGINAL,row.getString(2));
                },userId);
        // References must be owned independently. Do not remove a file shared with another user.
        for (var target : targets) {
            Long shared = jdbc.queryForObject("select count(*) from evidence e join reports r on r.id=e.report_id where r.user_id<>? and e.storage_key=?",Long.class,userId,target.key());
            Long sharedPhoto = jdbc.queryForObject("select count(*) from trueshot_photos p join trueshot_requests r on r.id=p.request_id where r.user_id<>? and (p.masked_image=? or p.original_storage_key=?)",Long.class,userId,target.key(),target.key());
            if (sharedPhoto != null) shared = (shared == null ? 0 : shared)+sharedPhoto;
            if (shared != null && shared>0) throw new ApiException(HttpStatus.CONFLICT,
                    "다른 계정과 공유된 사진 참조가 있어요. 관리자에게 문의해 주세요");
        }
        return List.copyOf(targets);
    }

    public void deleteOwnedRows(Long userId, String phoneHash) {
        jdbc.update("delete from dispute_items where report_id in (select id from reports where user_id=?)",userId);
        jdbc.update("delete from evidence where report_id in (select id from reports where user_id=?)",userId);
        jdbc.update("delete from report_identifiers where report_id in (select id from reports where user_id=?)",userId);
        jdbc.update("delete from reports where user_id=?",userId);
        jdbc.update("delete from trueshot_photos where request_id in (select id from trueshot_requests where user_id=?)",userId);
        jdbc.update("delete from trueshot_requests where user_id=?",userId);
        jdbc.update("delete from phone_verifications where phone_hash=? or purpose=?",phoneHash,"PHONE_CHANGE:"+userId);
    }

    private void add(List<StorageTarget> targets, StorageArea area, String key) {
        if (key != null) targets.add(new StorageTarget(area,key));
    }
}