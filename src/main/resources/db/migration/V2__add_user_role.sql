-- Issue #2: role-based access control. Adds the users.role column that
-- the AdminController @PreAuthorize("hasRole('ADMIN')") check depends on.
-- Defaulting existing rows to USER preserves prior behaviour (no one is
-- silently promoted to ADMIN); the not-null + check constraint mirrors
-- Hibernate's @Enumerated(STRING) mapping so validate passes.
alter table users
    add column role varchar(16) not null default 'USER'
    check (role in ('USER','ADMIN'));
