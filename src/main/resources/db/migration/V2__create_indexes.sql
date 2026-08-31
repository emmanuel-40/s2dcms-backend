
-- flyway: disableTransaction

-- Message table indexes


CREATE INDEX IF NOT EXISTS idx_message_student_sent_at
ON messages(student_id, sent_at);

CREATE INDEX IF NOT EXISTS idx_message_student_status_sent_at
ON messages(student_id, status, sent_at);

CREATE INDEX IF NOT EXISTS idx_message_department_sent_at
ON messages(department_id, sent_at);

CREATE INDEX IF NOT EXISTS idx_message_department_status_sent_at
ON messages(department_id, status, sent_at);


-- RefreshToken table indexes

CREATE UNIQUE INDEX IF NOT EXISTS idx_refresh_token_token
ON refreshtoken(token);

CREATE INDEX IF NOT EXISTS idx_refresh_token_expiry
ON refreshtoken(expiry_date);

CREATE INDEX IF NOT EXISTS idx_refresh_token_revoked_true
ON refreshtoken(expiry_date)
WHERE revoked = true;


-- UserActionLimit table index

CREATE INDEX IF NOT EXISTS idx_useraction_email_action
ON user_action_limit(email, action);


-- Student table indexes

CREATE UNIQUE INDEX IF NOT EXISTS idx_student_email
ON students(email);

CREATE INDEX IF NOT EXISTS idx_student_reset_token
ON students(password_reset_token);


-- Department table indexes

CREATE UNIQUE INDEX IF NOT EXISTS idx_department_email
ON departments(email);

CREATE INDEX IF NOT EXISTS idx_department_reset_token
ON departments(password_reset_token);