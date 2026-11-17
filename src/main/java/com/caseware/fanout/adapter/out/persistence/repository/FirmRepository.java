package com.caseware.fanout.adapter.out.persistence.repository;

import com.caseware.fanout.adapter.out.persistence.entity.FirmRow;
import org.springframework.data.repository.Repository;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;

/** SQL operations participate in the adapter transaction; never invoke these outside that boundary. */
public interface FirmRepository extends Repository<FirmRow, String> {
    @Query("SELECT COUNT(*) FROM firms WHERE firm=:firm")
    long countFor(String firm);

    @Query("INSERT INTO firms(firm) VALUES(:firm) ON CONFLICT (firm) DO NOTHING")
    @Modifying
    int insert(String firm);

    @Query("UPDATE firms SET last_turn=nextval('dispatch_turn') WHERE firm=:firm")
    @Modifying
    int turn(String firm);

}
