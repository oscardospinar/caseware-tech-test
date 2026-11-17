CREATE INDEX messages_due ON messages(visible_at, id);
CREATE INDEX tasks_active_firm ON tasks(firm) WHERE operation_id IS NOT NULL;
CREATE INDEX outbox_pending ON outbox(task_id) WHERE published=FALSE;
CREATE SEQUENCE dispatch_turn;
