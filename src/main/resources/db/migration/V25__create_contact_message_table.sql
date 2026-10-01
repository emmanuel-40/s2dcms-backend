
-- Contact form submissions
--
-- The ContactMessage entity had no migration of its own, so the table only ever
-- existed if Hibernate happened to auto-create it (spring.jpa.hibernate.ddl-auto=update).
-- On Supabase it does not exist, and every submission failed with:
--   ERROR: relation "contact_message" does not exist
-- which surfaced to the sender as the generic "Something went wrong, please try again later".
--
-- The table name is singular because Hibernate maps ContactMessage to contact_message
-- (PhysicalNamingStrategyStandardImpl + Spring's implicit CamelCase -> snake_case naming).
-- IF NOT EXISTS keeps this safe to apply over an environment where auto-DDL did create it.
-- 
CREATE TABLE IF NOT EXISTS contact_message (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    email VARCHAR(255),
    message VARCHAR(2000),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);