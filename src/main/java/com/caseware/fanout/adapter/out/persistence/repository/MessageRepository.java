package com.caseware.fanout.adapter.out.persistence.repository;

import com.caseware.fanout.adapter.out.persistence.entity.MessageRow;
import org.springframework.data.repository.Repository;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;

/** SQL operations participate in the adapter transaction; never invoke these outside that boundary. */
public interface MessageRepository extends Repository<MessageRow, Long> {
    @Query("INSERT INTO messages(task_id,visible_at) VALUES(:task,:visibleAt)")
    @Modifying
    int send(String task, long visibleAt);

    @Query("SELECT m.* FROM messages m JOIN tasks t ON t.id=m.task_id JOIN firms f ON f.firm=t.firm WHERE m.visible_at<=:now ORDER BY f.last_turn,m.id LIMIT 1 FOR UPDATE OF m SKIP LOCKED")
    MessageRow next(long now);

    @Query("UPDATE messages SET visible_at=:visibleAt,receipt=:receipt WHERE id=:id")
    @Modifying
    int receive(long id, String receipt, long visibleAt);

    @Query("DELETE FROM messages WHERE id=:id AND receipt=:receipt")
    @Modifying
    int acknowledge(long id, String receipt);

    @Query("UPDATE messages SET visible_at=:due,receipt=NULL WHERE id=:id AND receipt=:receipt")
    @Modifying
    int defer(long id, String receipt, long due);

    @Query("UPDATE messages SET visible_at=:due WHERE id=:id AND receipt=:receipt AND visible_at>:now")
    @Modifying
    int renew(long id, String receipt, long now, long due);

    @Query("SELECT COUNT(*) FROM messages")
    long count();

}
