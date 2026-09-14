INSERT INTO container_runs
(id, app_id, app_image, workflow_id, container_name, container_id, status, created_at, updated_at)
VALUES (1, 1, 'nginx:latest', 10, 'nginx-run-001', 'container-abc123', 'RUNNING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       (2, 2, 'redis:6.2', 20, 'redis-run-002', 'container-def456', 'FAILED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
       (3, 3, 'postgres:13', 30, 'postgres-run-003', 'container-ghi789', 'COMPLETED', CURRENT_TIMESTAMP,
        CURRENT_TIMESTAMP);

INSERT INTO container_logs
(log, container_run_id, created_at, updated_at)
VALUES
    -- Logs für Run 1 (ID = 1)
    ('Initializing container environment',        1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Started workflow step 1',                   1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Completed workflow step 1 successfully',    1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- Logs für Run 2 (ID = 2)
    ('Pulling image from registry',               2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Encountered error during startup',          2, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Retrying workflow step 2',                  2,
     CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),

    -- Logs für Run 3 (ID = 3)
    ('Verifying configuration parameters',        3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Executing database migrations',             3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('Container finished with warnings',          3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);


ALTER TABLE container_runs
    ALTER COLUMN id RESTART WITH 6;
ALTER TABLE container_logs
    ALTER COLUMN id RESTART WITH 11;