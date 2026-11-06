# Petsa de Sweldo – Payroll & Attendance (final UI)

Your `petsa_de_sweldo_final.zip` UI (React + TypeScript + Vite) connected to a **plain Java (JDBC)** API and a **MySQL** database.
No Maven, no Node backend, no other database.

```
petsa-de-sweldo-final/
├─ frontend/        your final UI (same design, CSS and navigation) + src/api.ts
├─ backend/
│  ├─ src/com/petsa/        Java source (JDK built-in HTTP server + JDBC, no libraries)
│  ├─ lib/                  <- put YOUR MySQL Connector/J JAR here (not included)
│  ├─ config/               application.properties.example  (copy → application.properties)
│  ├─ sql/                  schema.sql, upgrade_v1_to_v2.sql, sample_employees.sql, inspect_existing_schema.sql
│  ├─ scripts/              build / run / manage-users / test  (.sh and .bat)
│  └─ tests/                unit tests + integration test
├─ .vscode/         launch, tasks and Java settings
└─ README.md
```

## 1. Who can do what

| Role (login) | Pages | What it does |
|---|---|---|
| **System Admin** (`ADMIN-001`) | Dashboard, User Management, System History | create/edit employee accounts (type, salary, work schedule, password, **Account Status**), see the audit trail |
| **Finance Admin** (`FIN-001`) | Dashboard, Payroll Management, Approval & Release | salary information, payroll calculations, finance reports (print/CSV), approve → release payroll |
| **Employee** (employee ID) | Overview, Attendance, My Payslips, My Profile | Time In/Out, own history, own **released** payslips |

Access is enforced **on the server** for every request (wrong role → 403; employees can only ever read their own data, looked up from their session).

## 2. Install

| Tool | Version | Check |
|---|---|---|
| JDK | 17+ (built/tested with 21) | `java -version`, `javac -version` |
| Node.js | 22 (Vite 8 needs 20.19+/22.12+) | `node -v` |
| MySQL Server + Workbench | 8.x (5.7 also fine) | Workbench connects |
| VS Code + *Extension Pack for Java* | | |
| MySQL Connector/J JAR | the one you already use | |

Open the `petsa-de-sweldo-final` folder in VS Code.

## 3. Database (MySQL Workbench)

**Which script do I run?**

| Situation | Script |
|---|---|
| New install | `backend/sql/schema.sql` |
| You already ran the **first** version's `schema.sql` | `backend/sql/upgrade_v1_to_v2.sql` (do **not** run `schema.sql` again) |
| You want to reuse a database you already have | first run `backend/sql/inspect_existing_schema.sql` (read-only) and send me the result |

In Workbench: *File → Open SQL Script…* → choose the file → click ⚡ *Execute*.

* `schema.sql` creates its own database `petsa_de_sweldo` with `CREATE … IF NOT EXISTS`. No `DROP`/`DELETE`/`TRUNCATE`; safe to re-run.
* `upgrade_v1_to_v2.sql` only **adds**: new columns on `employees` (employment type + schedule), `FINANCE` in `users.role`, approval columns on `payroll`, a new `audit_log` table. Every step checks `information_schema` first, so it is safe to run twice.
  Existing employees get the default schedule 08:00–17:00 Mon–Fri with a 60-minute break; payroll of periods you had already "Processed" becomes `Released` so employees keep seeing it. The old `activity_log` table is left alone (no longer used; drop it yourself if you want).
* I verified that a v1 database upgraded by the script and a fresh `schema.sql` install end up with identical columns and indexes.

| Table | Purpose |
|---|---|
| `employees` | id, name, department, position, monthly salary, status, **employment type, shift start/end, workdays, break minutes** |
| `users` | logins (`ADMIN`, `FINANCE`, `EMPLOYEE`), PBKDF2 password hash only, lockout counters |
| `sessions` | server-side sessions (SHA-256 of the cookie token only) |
| `attendance` | one row per employee per day: time in / time out |
| `holidays` | optional paid holidays (starts empty) |
| `payroll_periods` | semi-monthly periods (1–15, 16–end) |
| `payroll` | per employee/period: days paid, basic, overtime, gross, deductions, net, **Pending/Approved/Released** + who/when |
| `audit_log` | System History (admin) and Finance activity |

Optional: `backend/sql/sample_employees.sql` inserts the 10 employees (with schedules) that were hardcoded in the UI (`INSERT IGNORE`).

## 4. Configure and create logins

1. Copy your Connector/J JAR into **`backend/lib/`**.
2. Copy `backend/config/application.properties.example` → **`backend/config/application.properties`**, fill in `db.user`/`db.password` (and `db.url` if needed).
   (Or set `PDS_DB_URL`, `PDS_DB_USER`, `PDS_DB_PASSWORD`.) This file is git-ignored and is not in the ZIP.
3. Create the two staff logins (you type the password at a prompt; only a hash is stored):
   * macOS/Linux/Git Bash: `backend/scripts/manage-users.sh create-admin ADMIN-001 "Administrator"` and `… create-finance FIN-001 "Finance Admin"`
   * Windows: `backend\scripts\manage-users.bat create-admin ADMIN-001 "Administrator"` and `… create-finance FIN-001 "Finance Admin"`
4. Employees you add in **User Management** get their login automatically (*Initial Password* field). For sample employees: `… create-login EMP-001`. Reset a password: `… set-password EMP-001` (or edit the employee and fill *New Password*).

> **Your own connection class:** all connections go through `Db.get()` in `backend/src/com/petsa/Db.java`. I did not have your existing class, so I wrote one that reads the settings above; replace the body of `Db.get()` with a call to yours if you prefer (or paste yours and I'll adapt it).

## 5. Run

**API:** `backend/scripts/run.sh` (Windows `backend\scripts\run.bat`) – or VS Code *Run and Debug → "Petsa de Sweldo API"*, or *Run Task → "Run everything"*.
Expect `Petsa de Sweldo API listening on http://127.0.0.1:8080`.

**UI:**
```
cd frontend
npm install
npm run dev
```
Open **http://localhost:8443**.

## 6. How payroll works (please check these rules)

* **Pay periods** are semi-monthly: 1st–15th and 16th–end of month.
* **Calculation is automatic.** Opening any Finance page recalculates the *current and previous* period from attendance. Only rows still **Pending** are recalculated; **Approved/Released rows are frozen**. (Your UI has no "Calculate" button, so I did not add one.)
* **Basic pay** = ½ of the monthly salary × (paid days ÷ scheduled days in the period).
  *Scheduled days* = days in the period that match the employee's **own** workdays. *Paid days* = scheduled days with a completed Time In **and** Time Out, plus scheduled days that are in the `holidays` table.
  For a normal Mon–Fri employee this is exactly your old formula (salary ÷ 22 × days): ₱36,000 → ₱18,000.00 for a full period, ₱16,363.64 for one missed day.
* **Overtime** = hours worked beyond the employee's **scheduled** hours each day (worked = time out − time in − their break) × hourly rate × 1.25, with hourly rate = salary ÷ 22 ÷ 8.
* **Late arrivals do not reduce pay** (your UI removed the late deduction). `deductions` exists in the table and defaults to 0; the UI has no screen to enter any.
* **Gross** = basic + overtime. **Net** = gross − deductions.
* Employees see a payslip **only after Finance releases it**. Release requires Approve first; the server rejects any other order.
* Add a holiday: `INSERT INTO holidays (holiday_date, name) VALUES ('2026-12-25', 'Christmas Day');`
* "Today", Time In and Time Out all use the **server's** clock/calendar (`app.timezone`, default Asia/Manila).

### Differences from your UI (design/CSS unchanged)

* **Edit Employee** has one extra field, **Account Status** (Active/Inactive; *Locked* is shown but can only be cleared by choosing Active). Your UI showed Active/Inactive/Locked counts on the dashboard but had no control to change them, and you asked for activation/deactivation. Inactive = login blocked immediately and the person is signed out; their attendance and payslips are kept.
  The status also controls payroll: an inactive employee gets no new payroll rows unless they actually worked in that period. **Locked** happens automatically after 5 wrong passwords (15 minutes).
* **Edit Employee** also has an optional **New Password** field (password reset).
* The dashboards, history, salary information, reports, approvals and payslips show database data instead of the hardcoded samples. Finance's dashboard figures cover the **current period**.
* Dead code from the previous design (old Overview/Employees/Attendance/Payroll/Reports pages that nothing opened any more) was removed from `App.tsx`; its CSS is untouched.
* `tsconfig.json`: `lib` raised from ES2020 to ES2022 because your code uses `String.replaceAll` (otherwise `tsc` fails; `vite build` was never affected).
* Texts that said "prototype" (confirm dialogs, report footer, toasts) now describe what really happens.

## 7. Security

Passwords: PBKDF2-HMAC-SHA256, 600,000 iterations, per-password salt, constant-time compare; identical error for unknown user vs wrong password; the "inactive" message is shown only after a correct password.
Sessions: random 256-bit token in an `HttpOnly; SameSite=Strict` cookie (set `server.cookieSecure=true` under HTTPS), only its hash stored, 8-hour expiry, revoked on logout/deactivation/password change.
Brute force: 5 wrong passwords lock the account 15 minutes (recorded in System History as *Attention*) plus a per-IP limit on failed logins.
CSRF: SameSite=Strict + required `X-Requested-With` header + `Origin` allow-list + JSON-only bodies. SQL injection: prepared statements only. XSS: React escaping + server-side validation of names/labels + `nosniff`, `frame-ancestors 'none'`, `no-store`.
Validation of every field (including schedules), 64 KB body limit, generic 500 messages, API bound to `127.0.0.1`. No secrets in the code or ZIP.

Before real use: HTTPS behind a reverse proxy, `cookieSecure=true`, set `server.allowedOrigins` to your real address, build the UI (`npm run build`) and serve `frontend/dist` from a web server that forwards `/api` to the Java API. (Behind a proxy every request shares the proxy's IP, so the per-IP limit becomes global; account lockout still works.)

## 8. API

| Method & path | Role | Purpose |
|---|---|---|
| `POST /api/auth/login`, `POST /api/auth/logout`, `GET /api/auth/me` | all | session |
| `GET /api/employees` | admin, finance | employee list |
| `POST /api/employees`, `PUT /api/employees/{id}`, `POST /api/employees/{id}/status` | admin | accounts |
| `GET /api/audit` | admin (System History), finance (payroll activity) | each role sees only its own category |
| `GET /api/attendance` | admin | all attendance (not used by the UI) |
| `GET /api/me/attendance`, `POST /api/me/attendance/time-in`, `…/time-out`, `GET /api/me/payslips` | employee | own data |
| `GET /api/finance/payroll`, `POST /api/finance/payroll/refresh`, `POST /api/finance/payroll/{id}/approve`, `…/release` | finance | payroll workflow |

`GET /api/finance/payroll` returns the last 12 months of periods.

## 9. Tests – what was and was not verified

`backend/scripts/test.sh` (or `.bat`) runs the unit tests (no database): payroll formulas incl. Mon–Sat and part-time schedules, password hashing, JSON.

`backend/tests/com/petsa/IntegrationTest.java` runs **122 checks over HTTP**: login/CSRF/lockout, the full role-access matrix, schedule validation, overtime against each employee's schedule, frozen Approved/Released rows, approval order, payslip visibility/isolation, audit separation, deactivate/unlock. **It writes and approves payroll**, so it refuses to run unless the database name contains `_test`: create a throw-away copy of `schema.sql` named `petsa_test`, start the API against it and run it with the same `PDS_DB_*` settings.

Done before packaging: unit tests, the integration test (twice in a row, 122/122 each), the v1→v2 upgrade script against a database containing data (twice), the real UI flows in a simulated DOM against the live API (System Admin: add/edit/deactivate/reactivate/history; Finance: salary info, calculations, report, approve, release; Employee: released payslip, Time In), `tsc` and `vite build`.

**Not verified – please check on your machine:**
* I used **MariaDB Connector/J against MySQL 8.0**, not Oracle's MySQL Connector/J (not obtainable here). Only standard JDBC is used, so it should work, but it is not proven.
* No real browser was available: layout/responsiveness were not re-checked visually. `index.css` is byte-identical to your upload and no class names were added.
* Printing/PDF and CSV download buttons were not exercised (they are your original code and only their data source changed).
* MySQL 5.7 and the `.bat` scripts were not run.

## 10. Troubleshooting

| Message | Fix |
|---|---|
| `CONFIG ERROR: Missing setting 'db.url'` | create `backend/config/application.properties` |
| `DATABASE ERROR … No suitable driver` | JAR missing from `backend/lib/` |
| `Unknown column 'employment_type'` / `Data truncated for column 'role'` | you have a v1 database: run `upgrade_v1_to_v2.sql` |
| `Unknown database` / `Table … doesn't exist` | run `schema.sql` |
| `Access denied for user` | wrong `db.user`/`db.password` |
| Browser says "Cannot reach the server" | the Java API is not running |
| "Origin not allowed" (403) | add the URL in your browser to `server.allowedOrigins` |
| Account locked | wait 15 min, or System Admin edits the employee and sets Account Status to Active |
