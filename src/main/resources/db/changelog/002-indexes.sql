CREATE INDEX IF NOT EXISTS messages_due ON messages(visible_at, id);
CREATE INDEX IF NOT EXISTS tasks_active ON tasks(operation_id, firm);
