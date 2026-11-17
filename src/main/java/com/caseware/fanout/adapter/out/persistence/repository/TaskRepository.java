package com.caseware.fanout.adapter.out.persistence.repository;

import com.caseware.fanout.adapter.out.persistence.entity.TaskRow;
import org.springframework.data.repository.Repository;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.jdbc.repository.query.Modifying;
import java.util.List;

/** SQL operations participate in the adapter transaction; never invoke these outside that boundary. */
public interface TaskRepository extends Repository<TaskRow, String> {
    @Query("SELECT * FROM tasks WHERE id=:id")
    TaskRow byId(String id);

    @Query("SELECT * FROM tasks WHERE id=:id FOR UPDATE")
    TaskRow lock(String id);

    @Query("SELECT * FROM tasks ORDER BY firm,engagement LIMIT :limit")
    List<TaskRow> inspect(int limit);

    @Query("SELECT COUNT(*) FROM tasks WHERE run_id=:run AND firm=:firm AND engagement=:engagement")
    long existsFor(String run, String firm, String engagement);

    @Query("INSERT INTO tasks(id,run_id,region,firm,engagement,required_revision,state,due_at) VALUES(:id,:run,:region,:firm,:engagement,:revision,'READY',:due)")
    @Modifying
    int insert(String id, String run, String region, String firm, String engagement, long revision, long due);

    @Query("UPDATE tasks SET state='RUNNING',token=token+1,lease_until=:until WHERE id=:id AND lease_until<=:now")
    @Modifying
    int claim(String id, long now, long until);

    @Query("UPDATE tasks SET lease_until=:until WHERE id=:id")
    @Modifying
    int renew(String id, long until);

    @Query("SELECT COUNT(*) FROM tasks WHERE operation_id IS NOT NULL")
    long activeCount();

    @Query("SELECT COUNT(*) FROM tasks WHERE operation_id IS NOT NULL AND firm=:firm")
    long activeForFirm(String firm);

    @Query("UPDATE tasks SET attempts=:attempt,operation_id=:operation,error_code=NULL WHERE id=:id")
    @Modifying
    int reserve(String id, int attempt, String operation);

    @Query("UPDATE tasks SET state='READY',lease_until=0,due_at=:due,error_code=:code WHERE id=:id")
    @Modifying
    int defer(String id, long due, String code);

    @Query("UPDATE tasks SET state=:state,operation_id=NULL,lease_until=0,due_at=:due,error_code=:code WHERE id=:id")
    @Modifying
    int complete(String id, String state, long due, String code);

    @Query("UPDATE tasks SET state='SUPERSEDED',lease_until=0 WHERE id=:id")
    @Modifying
    int supersede(String id);

    @Query("SELECT COUNT(*) FROM tasks WHERE id=:id AND token=:token AND lease_until>:now AND state='RUNNING'")
    long owns(String id, long token, long now);

}
