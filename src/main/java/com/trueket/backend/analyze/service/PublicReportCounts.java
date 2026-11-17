package com.trueket.backend.analyze.service;

import com.trueket.backend.analyze.dto.ReportLookup;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/** Internal boundary: IDs must come from exact server-side identifier matching. */
@Service
public class PublicReportCounts {
    private final NamedParameterJdbcTemplate jdbc;

    public PublicReportCounts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<ReportLookup.Target> count(Collection<Long> identifierIds, OffsetDateTime asOf) {
        var ids = identifierIds.stream().distinct().sorted().toList();
        if (ids.stream().anyMatch(id -> id <= 0)) {
            throw new IllegalArgumentException("식별자 ID는 양수여야 합니다.");
        }
        if (ids.isEmpty()) return List.of();
        var params = new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("since", asOf.minusYears(1))
                .addValue("asOf", asOf);
        return jdbc.query("""
                SELECT i.id,
                       COUNT(DISTINCT CASE WHEN r.kind = 'FRAUD' THEN r.reporter_hash END) AS damage_people,
                       COUNT(DISTINCT CASE WHEN r.kind = 'SUSPICION' THEN r.reporter_hash END) AS suspect_people
                FROM identifiers i
                LEFT JOIN report_identifiers ri ON ri.identifier_id = i.id AND ri.hidden = false
                LEFT JOIN reports r ON r.id = ri.report_id AND r.status IN ('PUBLISHED', 'RESPONDING', 'REVIEWING')
                    AND r.created_at >= :since AND r.created_at <= :asOf
                WHERE i.id IN (:ids)
                GROUP BY i.id
                ORDER BY i.id
                """, params, (rs, row) -> new ReportLookup.Target(
                Long.toString(rs.getLong("id")), rs.getLong("damage_people"), rs.getLong("suspect_people")));
    }
}
