package com.caseware.fanout.adapter.out.persistence.repository;

import com.caseware.fanout.adapter.out.persistence.entity.ControlRow;
import org.springframework.data.repository.Repository;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;

/** SQL operations participate in the adapter transaction; never invoke these outside that boundary. */
public interface ControlRepository extends Repository<ControlRow, Integer> {
    @Query("SELECT * FROM control WHERE id=1 FOR UPDATE")
    ControlRow lock();

    @Query("SELECT * FROM control WHERE id=1")
    ControlRow current();

    @Query("INSERT INTO control(id,region,config) VALUES(1,:region,:config) ON CONFLICT (id) DO NOTHING")
    @Modifying
    int initialize(String region, String config);

    @Query("SELECT floor(extract(epoch FROM clock_timestamp()) * 1000)::bigint")
    long currentTimeMillis();

    @Query("UPDATE control SET next_start=:due WHERE id=1")
    @Modifying
    int nextStart(long due);

    @Query("UPDATE control SET probe_task=:task WHERE id=1")
    @Modifying
    int probe(String task);

    @Query("UPDATE control SET failures=failures+1 WHERE id=1")
    @Modifying
    int failure();

    @Query("UPDATE control SET open_until=:due WHERE id=1")
    @Modifying
    int openUntil(long due);

    @Query("UPDATE control SET failures=0,open_until=0,probe_task=NULL WHERE id=1")
    @Modifying
    int healthy();

}
