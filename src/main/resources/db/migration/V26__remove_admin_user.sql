-- Remove admin user from departments (admin managed via direct SQL, not migrations)
-- Rate limiting still applies to admin on next login attempt

DELETE FROM user_action_limit WHERE email = 'admin@s2dcms.com';
DELETE FROM departments WHERE email = 'admin@s2dcms.com';