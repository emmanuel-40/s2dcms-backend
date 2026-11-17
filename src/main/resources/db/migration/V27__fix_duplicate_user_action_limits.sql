-- Remove duplicate user_action_limit rows, keeping the most recent per (email, action)
DELETE FROM user_action_limit u1
USING user_action_limit u2
WHERE u1.id < u2.id
  AND u1.email = u2.email
  AND u1.action = u2.action;

-- Prevent future duplicates at the database level
ALTER TABLE user_action_limit
    ADD CONSTRAINT uk_user_action_limit_email_action UNIQUE (email, action);
