package com.caseware.fanout.adapter.out.persistence.repository;

import com.caseware.fanout.adapter.out.persistence.entity.OutboxRow;
import org.springframework.data.repository.Repository;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;
import java.util.List;

/** SQL operations participate in the adapter transaction; never invoke these outside that boundary. */
public interface OutboxRepository extends Repository<OutboxRow, String> {
    @Query("SELECT task_id FROM outbox WHERE published=FALSE ORDER BY task_id LIMIT :limit")
    List<String> unpublished(int limit);

    @Query("INSERT INTO outbox(task_id) VALUES(:task)")
    @Modifying
    int insert(String task);

    @Query("UPDATE outbox SET published=TRUE WHERE task_id=:task")
    @Modifying
    int published(String task);

}
