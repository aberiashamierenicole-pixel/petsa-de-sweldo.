-- =====================================================================
-- Petsa de Sweldo - database schema, version 2 (NON-DESTRUCTIVE, fresh install)
--
--  Already ran the version-1 schema? Do NOT re-run this for upgrading - run
--  sql/upgrade_v1_to_v2.sql instead (it adds the new columns/tables without touching data).
--
--  * Creates its own database `petsa_de_sweldo` only if it does not exist.
--  * Every table uses CREATE TABLE IF NOT EXISTS.
--  * There is no DROP, TRUNCATE, DELETE or ALTER of existing objects.
--  * Re-running this script is safe.
--
-- If you want these tables inside a database you already have, change the
-- two database-name lines below AND `db.url` in backend/config/application.properties,
-- and first run sql/inspect_existing_schema.sql to check for name clashes
-- (an existing table named `employees`, `attendance`, `users`... would be
-- skipped by IF NOT EXISTS and then not match what the app expects).
--
-- Compatible with MySQL 5.7+ and 8.x (no CHECK constraints, no JSON columns).
-- =====================================================================
CREATE DATABASE IF NOT EXISTS petsa_de_sweldo
  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE petsa_de_sweldo;

CREATE TABLE IF NOT EXISTS employees (
  id              VARCHAR(20)   NOT NULL,                 -- e.g. EMP-001
  full_name       VARCHAR(100)  NOT NULL,
  department      VARCHAR(60)   NOT NULL,
  position_title  VARCHAR(60)   NOT NULL,
  monthly_salary  DECIMAL(12,2) NOT NULL,
  status          ENUM('Active','Inactive') NOT NULL DEFAULT 'Active',
  employment_type ENUM('Full-Time','Part-Time','Agency-Hired') NOT NULL DEFAULT 'Full-Time',
  shift_start     TIME          NOT NULL DEFAULT '08:00:00',
  shift_end       TIME          NOT NULL DEFAULT '17:00:00',
  workdays        VARCHAR(27)   NOT NULL DEFAULT 'Mon,Tue,Wed,Thu,Fri',   -- comma-separated Mon..Sun
  break_minutes   SMALLINT UNSIGNED NOT NULL DEFAULT 60,
  created_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_employees_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Login accounts. Passwords are stored ONLY as salted PBKDF2 hashes.
CREATE TABLE IF NOT EXISTS users (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  login_id         VARCHAR(30)  NOT NULL,                 -- ADMIN-001, FIN-001 or the employee ID
  role             ENUM('ADMIN','FINANCE','EMPLOYEE') NOT NULL,
  employee_id      VARCHAR(20)  NULL,                     -- NULL for admins
  display_name     VARCHAR(100) NOT NULL,
  password_hash    VARCHAR(255) NOT NULL,
  failed_attempts  INT          NOT NULL DEFAULT 0,
  locked_until     DATETIME     NULL,                     -- UTC
  created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_users_login (login_id),
  UNIQUE KEY uq_users_employee (employee_id),
  CONSTRAINT fk_users_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Server-side login sessions. Only a SHA-256 hash of the cookie token is stored.
CREATE TABLE IF NOT EXISTS sessions (
  token_hash  CHAR(64)  NOT NULL,
  user_id     BIGINT    NOT NULL,
  created_at  DATETIME  NOT NULL,                         -- UTC
  expires_at  DATETIME  NOT NULL,                         -- UTC
  PRIMARY KEY (token_hash),
  KEY idx_sessions_user (user_id),
  KEY idx_sessions_expires (expires_at),
  CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS attendance (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  employee_id  VARCHAR(20) NOT NULL,
  work_date    DATE        NOT NULL,
  time_in      TIME        NOT NULL,
  time_out     TIME        NULL,
  created_at   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uq_attendance_day (employee_id, work_date),  -- one record per employee per day
  KEY idx_attendance_date (work_date),
  CONSTRAINT fk_attendance_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Optional: weekday holidays are paid for everyone. Starts empty; add rows yourself, e.g.
--   INSERT INTO holidays (holiday_date, name) VALUES ('2026-12-25', 'Christmas Day');
CREATE TABLE IF NOT EXISTS holidays (
  holiday_date  DATE         NOT NULL,
  name          VARCHAR(100) NOT NULL,
  PRIMARY KEY (holiday_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Semi-monthly pay periods (1st-15th and 16th-end of month), created on demand.
CREATE TABLE IF NOT EXISTS payroll_periods (
  id            INT      NOT NULL AUTO_INCREMENT,
  start_date    DATE     NOT NULL,
  end_date      DATE     NOT NULL,
  status        ENUM('Open','Processed') NOT NULL DEFAULT 'Open',
  processed_at  DATETIME NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_period (start_date, end_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- One row per employee per pay period. Finance moves each row Pending -> Approved -> Released;
-- employees can only see their payslip once it is Released.
-- late_deduction is kept for compatibility with version 1 and is always 0 (late arrivals no longer reduce pay).
CREATE TABLE IF NOT EXISTS payroll (
  id               BIGINT        NOT NULL AUTO_INCREMENT,
  period_id        INT           NOT NULL,
  employee_id      VARCHAR(20)   NOT NULL,
  days_paid        INT           NOT NULL,
  basic            DECIMAL(12,2) NOT NULL,
  overtime         DECIMAL(12,2) NOT NULL,
  late_deduction   DECIMAL(12,2) NOT NULL DEFAULT 0,
  gross            DECIMAL(12,2) NOT NULL,
  deductions       DECIMAL(12,2) NOT NULL DEFAULT 0,
  net              DECIMAL(12,2) NOT NULL,
  calculated_at    DATETIME      NOT NULL,
  approval_status  ENUM('Pending','Approved','Released') NOT NULL DEFAULT 'Pending',
  status_updated_at DATETIME     NULL,
  approved_by      VARCHAR(30)   NULL,
  approved_at      DATETIME      NULL,
  released_by      VARCHAR(30)   NULL,
  released_at      DATETIME      NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uq_payroll_emp_period (period_id, employee_id),
  KEY idx_payroll_employee (employee_id),
  KEY idx_payroll_status (approval_status),
  CONSTRAINT fk_payroll_period   FOREIGN KEY (period_id)   REFERENCES payroll_periods (id),
  CONSTRAINT fk_payroll_employee FOREIGN KEY (employee_id) REFERENCES employees (id) ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Audit trail. SYSTEM entries feed "System History" (admin); PAYROLL entries feed the Finance activity list.
CREATE TABLE IF NOT EXISTS audit_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  occurred_at  DATETIME     NOT NULL,
  category     ENUM('SYSTEM','PAYROLL') NOT NULL,
  actor        VARCHAR(30)  NOT NULL,                      -- login ID, or 'System'
  action       VARCHAR(40)  NOT NULL,
  target       VARCHAR(40)  NOT NULL,
  details      VARCHAR(255) NOT NULL,
  result       ENUM('Success','Attention') NOT NULL DEFAULT 'Success',
  PRIMARY KEY (id),
  KEY idx_audit_category (category, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
