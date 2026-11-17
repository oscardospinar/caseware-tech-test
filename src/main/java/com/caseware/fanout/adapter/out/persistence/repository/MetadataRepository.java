package com.caseware.fanout.adapter.out.persistence.repository;

import com.caseware.fanout.adapter.out.persistence.entity.MetadataRow;
import org.springframework.data.repository.Repository;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;

/** SQL operations participate in the adapter transaction; never invoke these outside that boundary. */
public interface MetadataRepository extends Repository<MetadataRow, String> {
    @Query("SELECT * FROM metadata WHERE firm=:firm AND engagement=:engagement")
    MetadataRow read(String firm, String engagement);

    @Query("SELECT 1 FROM pg_advisory_xact_lock(hashtextextended(:key, 0))")
    int lock(String key);

    @Query("INSERT INTO metadata(firm,engagement,revision,applied_version) VALUES(:firm,:engagement,:revision,:version) ON CONFLICT (firm,engagement) DO UPDATE SET revision=EXCLUDED.revision,applied_version=EXCLUDED.applied_version")
    @Modifying
    int upsert(String firm, String engagement, long revision, String version);

}
