package com.caseware.fanout.adapter.out.persistence.repository;

import com.caseware.fanout.adapter.out.persistence.entity.RunRow;
import org.springframework.data.repository.Repository;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;

/** SQL operations participate in the adapter transaction; never invoke these outside that boundary. */
public interface RunRepository extends Repository<RunRow, String> {
    @Query("SELECT * FROM runs WHERE pub_id=:publication")
    RunRow byPublication(String publication);

    @Query("SELECT * FROM runs WHERE pub_id=:publication FOR UPDATE")
    RunRow lock(String publication);

    @Query("SELECT * FROM runs WHERE pub_id=:publication FOR SHARE")
    RunRow lockForExecution(String publication);

    @Query("SELECT * FROM runs WHERE id=:id")
    RunRow byId(String id);

    @Query("INSERT INTO runs(id,pub_id,product,target) VALUES(:id,:publication,:product,:target) ON CONFLICT (pub_id) DO NOTHING")
    @Modifying
    int insert(String id, String publication, String product, String target);

    @Query("UPDATE runs SET cursor_value=:cursor,finished=:finished WHERE id=:id")
    @Modifying
    int checkpoint(String id, String cursor, boolean finished);

    @Query("UPDATE runs SET active=FALSE WHERE pub_id=:publication")
    @Modifying
    int withdraw(String publication);

}
