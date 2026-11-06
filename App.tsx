import { FormEvent, useEffect, useState } from 'react'
import { api, serverToday, type Attendance, type AuditEntry, type Employee, type FinancePayroll, type Me, type Payroll, type WorkSchedule, type Workday } from './api'

type Page = 'Dashboard' | 'User Management' | 'System History'
type AuthRole = 'admin' | 'employee' | 'finance'
type EmployeePage = 'Overview' | 'Attendance' | 'My Payslips' | 'My Profile'
type FinancePage = 'Dashboard' | 'Payroll Management' | 'Approval & Release'
type AttendanceStatus = 'Present' | 'Absent'

const peso = new Intl.NumberFormat('en-PH', { style: 'currency', currency: 'PHP', maximumFractionDigits: 2 })
const localISO = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
const todayISO = () => serverToday() || localISO(new Date())
const errorMessage = (e: unknown) => (e instanceof Error ? e.message : 'Something went wrong. Please try again.')
const fallbackSchedule: WorkSchedule = { shiftStart: '08:00', shiftEnd: '17:00', workdays: ['Mon', 'Tue', 'Wed', 'Thu', 'Fri'], breakMinutes: 60 }
const financeOvertime = (row: FinancePayroll) => Number.isFinite(row.overtime) ? row.overtime : 0

const minutes = (time: string) => {
  if (!time) return 0
  const [h, m] = time.split(':').map(Number)
  return h * 60 + m
}
const employeeSchedule = (employee?: Employee): WorkSchedule => employee?.schedule || fallbackSchedule
const scheduledHours = (schedule: WorkSchedule) => Math.max(0, (minutes(schedule.shiftEnd) - minutes(schedule.shiftStart) - schedule.breakMinutes) / 60)
const metrics = (a: Attendance, schedule = fallbackSchedule) => {
  if (!a.timeIn || !a.timeOut) return { hours: 0, overtime: 0 }
  const hours = Math.max(0, (minutes(a.timeOut) - minutes(a.timeIn) - schedule.breakMinutes) / 60)
  return { hours, overtime: Math.max(0, hours - scheduledHours(schedule)) }
}
const isScheduledWorkday = (employee: Employee | undefined, date: string) => {
  const day = (['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'] as Workday[])[new Date(`${date}T00:00:00`).getDay()]
  return employeeSchedule(employee).workdays.includes(day)
}
const attendanceStatus = (record?: Attendance, employee?: Employee, date = todayISO()): AttendanceStatus | null => {
  if (record?.timeIn) return 'Present'
  return isScheduledWorkday(employee, date) ? 'Absent' : null
}
const punctuality = (record: Attendance | undefined, employee?: Employee) => record?.timeIn ? (minutes(record.timeIn) <= minutes(employeeSchedule(employee).shiftStart) ? 'On Time' : 'Late') : '—'
const attendanceRowsForDate = (employees: Employee[], rows: Attendance[], date: string) => {
  const activeEmployees = employees.filter(employee => (employee.status || 'Active') === 'Active')
  return activeEmployees.flatMap(employee => {
    const record = rows.find(row => row.employeeId === employee.id && row.date === date)
    if (record) return [record]
    if (!isScheduledWorkday(employee, date)) return []
    return [{
    id: `ABSENT-${date}-${employee.id}`,
    employeeId: employee.id,
    date,
    timeIn: '',
    timeOut: '',
    }]
  })
}
const scheduleShift = (employee?: Employee) => {
  const schedule = employeeSchedule(employee)
  return `${fmtTime(schedule.shiftStart)} – ${fmtTime(schedule.shiftEnd)}`
}
const scheduleWorkdays = (employee?: Employee) => employeeSchedule(employee).workdays.join(', ')
const scheduleBreak = (employee?: Employee) => {
  const duration = employeeSchedule(employee).breakMinutes
  return duration ? `${duration} minutes` : 'No scheduled break'
}
const workdayOptions: Workday[] = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
const fmtTime = (value: string) => {
  if (!value) return '—'
  const [h, m] = value.split(':').map(Number)
  return `${h % 12 || 12}:${String(m).padStart(2, '0')} ${h >= 12 ? 'PM' : 'AM'}`
}
const fmtDate = (value: string | Date = new Date()) => {
  const date = typeof value === 'string' ? new Date(`${value}T00:00:00`) : value
  return date.toLocaleDateString('en-US', { month: '2-digit', day: '2-digit', year: '2-digit' })
}
const shortDateToISO = (value: string) => {
  const [month, day, year] = value.split('/')
  return month && day && year ? `20${year}-${month}-${day}` : ''
}

function Icon({ name, size = 18 }: { name: string; size?: number }) {
  const paths: Record<string, React.ReactNode> = {
    search: <><circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/></>,
    plus: <><path d="M12 5v14M5 12h14"/></>,
    users: <><path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M22 21v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>,
    money: <><rect x="3" y="6" width="18" height="12" rx="2"/><circle cx="12" cy="12" r="2.5"/><path d="M7 9h.01M17 15h.01"/></>,
    activity: <><path d="M4 18V9M10 18V5M16 18v-7M22 18V3"/></>,
    chevron: <path d="m9 18 6-6-6-6"/>,
    close: <><path d="m6 6 12 12M18 6 6 18"/></>,
    edit: <><path d="M12 20h9"/><path d="m16.5 3.5 4 4L9 19l-5 1 1-5Z"/></>,
    trash: <><path d="M4 7h16M9 7V4h6v3M7 7l1 14h8l1-14"/></>,
    print: <><path d="M6 9V3h12v6M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="7"/></>,
    eye: <><path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12"/><circle cx="12" cy="12" r="2.5"/></>,
    eyeoff: <><path d="m3 3 18 18M10.6 6.2A10.7 10.7 0 0 1 12 6c6.5 0 10 6 10 6a17 17 0 0 1-2.2 3M6.4 6.4C3.5 8.2 2 12 2 12s3.5 6 10 6a10 10 0 0 0 3-.4M10.6 10.6a2 2 0 0 0 2.8 2.8"/></>,
    logout: <><path d="M10 17l5-5-5-5M15 12H3"/><path d="M14 3h5a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-5"/></>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/></>,
  }
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>{paths[name]}</svg>
}

function Button({ children, kind = 'primary', ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & { kind?: 'primary' | 'secondary' | 'ghost' | 'danger' }) {
  return <button className={`btn ${kind}`} {...props}>{children}</button>
}
function Input(props: React.InputHTMLAttributes<HTMLInputElement>) { return <input className="field" {...props}/> }
function Select(props: React.SelectHTMLAttributes<HTMLSelectElement>) { return <select className="field" {...props}/> }
function DatePicker({ value, onChange, label }: { value: string; onChange: (value: string) => void; label: string }) {
  return <div className="date-picker">
    <span>{value ? fmtDate(value) : 'MM/DD/YY'}</span>
    <Icon name="calendar" size={16}/>
    <input type="date" value={value} onChange={event => onChange(event.target.value)} aria-label={label}/>
  </div>
}
function Title({ children, sub }: { children: React.ReactNode; sub?: string }) {
  return <div><div className="page-title">{children}</div>{sub && <div className="page-sub">{sub}</div>}</div>
}
function Search({ value, onChange, placeholder }: { value: string; onChange: (v: string) => void; placeholder: string }) {
  return <div className="search"><Icon name="search" size={16}/><Input value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}/></div>
}
function MoneyCell({ value, strong = false }: { value: number; strong?: boolean }) {
  return <span className={strong ? 'money-strong' : ''}>{peso.format(value)}</span>
}

export default function App() {
  const [me, setMe] = useState<Me | null>(null)
  const [booting, setBooting] = useState(true)
  const [profileOpen, setProfileOpen] = useState(false)
  const [page, setPage] = useState<Page>('Dashboard')
  const [employees, setEmployees] = useState<Employee[]>([])
  const [history, setHistory] = useState<AuditEntry[]>([])
  const [notice, setNotice] = useState('')

  useEffect(() => {
    api.me().then(setMe).catch(() => setMe(null)).finally(() => setBooting(false))
    const expired = () => setMe(null)
    window.addEventListener('pds-unauthorized', expired)
    return () => window.removeEventListener('pds-unauthorized', expired)
  }, [])
  useEffect(() => {
    if (!notice) return
    const t = window.setTimeout(() => setNotice(''), 3200)
    return () => window.clearTimeout(t)
  }, [notice])

  const refreshHistory = async () => { try { setHistory(await api.audit()) } catch { /* history is informational */ } }
  const refreshEmployees = async () => { try { setEmployees(await api.employees()) } catch (e) { setNotice(errorMessage(e)) } }
  useEffect(() => {
    if (me?.role !== 'admin') return
    void refreshEmployees(); void refreshHistory()
  }, [me])
  // Account status (e.g. Locked) can change by itself, so refresh whenever the admin opens a page
  useEffect(() => { if (me?.role === 'admin') { void refreshEmployees(); void refreshHistory() } }, [page])

  const logout = async () => {
    try { await api.logout() } catch { /* the cookie is cleared server-side when reachable */ }
    setProfileOpen(false); setPage('Dashboard'); setMe(null); setEmployees([]); setHistory([])
  }

  const nav: Page[] = ['Dashboard', 'User Management', 'System History']
  if (booting) return null
  if (!me) return <Login onSuccess={setMe}/>
  if (me.role === 'employee' && me.employee) return <EmployeePortal employee={me.employee} logout={logout}/>
  if (me.role === 'finance') return <FinancePortal logout={logout}/>
  return (
    <main className="app-shell">
      <section className="app">
        <header className="topbar">
          <button className="brand" onClick={() => setPage('Dashboard')}>
            <span className="brand-mark"><img src="/assets/petsa-de-sweldo-logo.png" alt="Petsa de Sweldo logo"/></span>
            <span><b>Petsa de Sweldo</b><small>Payroll & Attendance</small></span>
          </button>
          <nav>{nav.map(n => <button key={n} onClick={() => setPage(n)} className={page === n ? 'active' : ''}>{n}</button>)}</nav>
          <div className="admin">
            <button className="icon-btn" aria-label="Search employee accounts" onClick={() => setPage('User Management')}></button>
            <button className="admin-profile" onClick={() => setProfileOpen(open => !open)} aria-expanded={profileOpen} aria-haspopup="menu">
              <span className="avatar">AD</span>
              <span><b>Administrator</b><small>System Admin</small></span>
            </button>
            {profileOpen && <div className="profile-menu" role="menu">
              <div><b>Administrator</b><small>Signed in as admin</small></div>
              <button role="menuitem" onClick={() => void logout()}><Icon name="logout" size={16}/> Log out</button>
            </div>}
          </div>
        </header>
        <div className="content">
          {page === 'Dashboard' && <SystemAdminDashboard employees={employees} history={history} setPage={setPage}/>}
          {page === 'User Management' && <UserManagement employees={employees} setEmployees={setEmployees} notify={setNotice} onChanged={refreshHistory}/>}
          {page === 'System History' && <SystemHistory history={history}/>}
        </div>
      </section>
      {notice && <div className="toast">{notice}</div>}
    </main>
  )
}

function Login({ onSuccess }: { onSuccess: (me: Me) => void }) {
  const [employeeId, setEmployeeId] = useState('')
  const [password, setPassword] = useState('')
  const [visible, setVisible] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    setError('')
    if (!employeeId.trim() || !password) {
      setError('Enter your employee ID and password.')
      return
    }
    setLoading(true)
    try {
      onSuccess(await api.login(employeeId.trim(), password))
    } catch (e) {
      setError(errorMessage(e))
      setLoading(false)
    }
  }

  return <main className="login-page">
    <div className="login-brand">
      <img src="/assets/petsa-de-sweldo-logo.png" alt="Petsa de Sweldo logo"/>
      <span><b>Petsa de Sweldo</b><small>Payroll & Attendance</small></span>
    </div>
    <div className="login-layout">
      <section className="login-card">
        <div className="login-copy">
          <div className="login-heading">Welcome Back!</div>
          <div className="login-subtitle">Log in to manage your payroll and attendance.</div>
        </div>
        <form className="login-form" onSubmit={submit}>
          <label>Employee ID
            <Input autoComplete="username" value={employeeId} onChange={e => setEmployeeId(e.target.value)} placeholder="Enter your employee ID" disabled={loading}/>
          </label>
          <label>Password
            <span className="password-field">
              <Input type={visible ? 'text' : 'password'} autoComplete="current-password" value={password} onChange={e => setPassword(e.target.value)} placeholder="Enter your password" disabled={loading}/>
              <button type="button" className="password-toggle" onClick={() => setVisible(x => !x)} aria-label={visible ? 'Hide password' : 'Show password'}><Icon name={visible ? 'eyeoff' : 'eye'} size={17}/></button>
            </span>
          </label>
          {error && <div className="login-error" role="alert">{error}</div>}
          <Button type="submit" disabled={loading}>{loading ? <><span className="spinner"/> Logging in…</> : 'Log In'}</Button>
        </form>
        <div className="login-footer">Authorized personnel only.</div>
      </section>
      <div className="login-art" aria-label="Filipino payroll and payday illustration">
        <PaydayIllustration/>
      </div>
    </div>
  </main>
}

function PaydayIllustration() {
  return <svg viewBox="0 0 640 660" role="img" aria-labelledby="payday-title payday-desc">
    <title id="payday-title">Filipino employee celebrating payday</title>
    <desc id="payday-desc">An office employee holding a salary envelope, surrounded by a payroll document, calendar, calculator, coins, and Philippine peso symbols.</desc>
    <defs>
      <clipPath id="frameClip"><rect x="24" y="20" width="592" height="620" rx="54"/></clipPath>
      <linearGradient id="shirt" x1="0" y1="0" x2="1" y2="1"><stop stopColor="#FFC16D"/><stop offset="1" stopColor="#F39B4C"/></linearGradient>
    </defs>
    <rect x="24" y="20" width="592" height="620" rx="54" fill="#07965B"/>
    <g clipPath="url(#frameClip)">
      <rect x="77" y="72" width="486" height="516" rx="34" fill="#FFF0E8"/>
      <circle cx="492" cy="125" r="118" fill="#F58BB5" opacity=".9"/>
      <path d="M77 508c88-68 160-74 240-22 89 57 164 50 246-22v124H77Z" fill="#D9F1E3"/>
      <path d="M77 550c101-58 194-51 272-5 72 42 136 25 214-20v63H77Z" fill="#BDE5CE"/>

      <g transform="translate(94 105)">
        <rect width="132" height="164" rx="18" fill="#fff" stroke="#245C46" strokeWidth="5"/>
        <path d="M0 38h132" stroke="#245C46" strokeWidth="5"/>
        <path d="M31 0v22M101 0v22" stroke="#245C46" strokeWidth="10" strokeLinecap="round"/>
        <text x="24" y="72" fill="#737B73" fontSize="16" fontWeight="700">PAYDAY</text>
        <rect x="18" y="89" width="42" height="42" rx="10" fill="#F58BB5"/>
        <rect x="72" y="89" width="42" height="42" rx="10" fill="#07965B"/>
        <text x="29" y="117" fill="#202820" fontSize="20" fontWeight="700">15</text>
        <text x="78" y="117" fill="#fff" fontSize="20" fontWeight="700">30</text>
      </g>

      <g transform="translate(420 378) rotate(7)">
        <rect width="114" height="148" rx="18" fill="#245C46"/>
        <rect x="16" y="18" width="82" height="32" rx="8" fill="#E6F3EB"/>
        {[0,1,2].map(row => [0,1,2].map(col => <rect key={`${row}-${col}`} x={18 + col * 29} y={65 + row * 25} width="20" height="17" rx="5" fill={row === 2 && col === 2 ? '#F58BB5' : '#fff'} opacity=".95"/>))}
      </g>

      <g transform="translate(204 112)">
        <ellipse cx="162" cy="440" rx="132" ry="28" fill="#245C46" opacity=".12"/>
        {/* Balanced lower body with legs connected naturally at the hips */}
        <path d="M119 394c13-9 35-13 63-13 27 0 49 4 64 13l-4 45h-57l-4-26-5 26h-59Z" fill="#245C46" stroke="#174534" strokeWidth="5" strokeLinejoin="round"/>
        <path d="M124 433h53l-7 75h-69l12-67c1-5 5-8 11-8Z" fill="#2D443B"/>
        <path d="M187 433h51c6 0 10 3 11 9l14 66h-70Z" fill="#354F45"/>
        <path d="M102 500h68v28H82c-10 0-14-6-10-14 5-9 15-14 30-14ZM194 500h69c15 0 25 5 30 14 4 8 0 14-10 14h-89Z" fill="#202820"/>
        <path d="M116 439h61M188 439h55" stroke="#17372A" strokeWidth="4" strokeLinecap="round"/>

        {/* Shirt follows the shoulder, chest, and waist silhouette */}
        <path d="M118 210c17-22 39-33 65-33 29 0 52 11 69 34 13 26 19 65 17 116l-18 73c-20 8-43 12-69 12-27 0-50-4-70-12l-17-74c-2-50 6-89 23-116Z" fill="url(#shirt)" stroke="#245C46" strokeWidth="6" strokeLinejoin="round"/>
        <path d="M151 179c8-9 18-14 31-14 14 0 25 5 33 15l-8 31h-49Z" fill="#B96F4B" stroke="#245C46" strokeWidth="5"/>

        {/* Head, ears, hair, and aligned facial features */}
        <path d="M126 103c0-48 22-76 57-76 36 0 59 29 59 76v25c0 39-25 66-59 66-33 0-57-27-57-66Z" fill="#C77B50" stroke="#245C46" strokeWidth="5"/>
        <ellipse cx="124" cy="121" rx="13" ry="20" fill="#C77B50" stroke="#245C46" strokeWidth="4"/>
        <ellipse cx="244" cy="121" rx="13" ry="20" fill="#C77B50" stroke="#245C46" strokeWidth="4"/>
        <path d="M128 105c-7-46 14-82 54-84 43-2 68 30 61 84-9-22-21-37-38-47-17 18-43 30-77 35Z" fill="#202820"/>
        <path d="M130 101c18-2 41-12 74-39 13 8 25 22 36 43" fill="none" stroke="#202820" strokeWidth="12" strokeLinecap="round"/>
        <path d="M146 104c8-5 16-5 24-1M198 103c8-4 17-3 24 2" fill="none" stroke="#3D2A25" strokeWidth="4" strokeLinecap="round"/>
        <ellipse cx="159" cy="119" rx="5" ry="6" fill="#202820"/><ellipse cx="210" cy="119" rx="5" ry="6" fill="#202820"/>
        <circle cx="160" cy="117" r="1.5" fill="#fff"/><circle cx="211" cy="117" r="1.5" fill="#fff"/>
        <path d="M184 120c-1 9-3 16-6 21 4 3 9 3 14 1" fill="none" stroke="#9E5D43" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round"/>
        <path d="M168 157c10 8 22 8 33 0" fill="none" stroke="#7B3F35" strokeWidth="4" strokeLinecap="round"/>
        <path d="M125 119v38M242 119v38" stroke="#202820" strokeWidth="6" strokeLinecap="round"/>

        {/* Articulated arms bend naturally toward the envelope */}
        <path d="M119 219c-25 6-40 24-45 53-4 25 3 48 20 69l28 35 31-24-27-37c-7-10-9-20-6-31l17-49Z" fill="#C77B50" stroke="#245C46" strokeWidth="6" strokeLinejoin="round"/>
        <path d="M249 219c25 7 39 26 43 54 3 25-5 49-22 69l-28 34-30-25 26-37c7-10 9-21 7-31l-14-49Z" fill="#C77B50" stroke="#245C46" strokeWidth="6" strokeLinejoin="round"/>

        <g transform="translate(119 281) rotate(-2)">
          <rect width="133" height="105" rx="12" fill="#fff" stroke="#245C46" strokeWidth="5"/>
          <path d="m5 15 62 46 61-46" fill="#E6F3EB" stroke="#245C46" strokeWidth="4"/>
          <circle cx="67" cy="62" r="25" fill="#07965B"/>
          <text x="53" y="71" fill="#fff" fontSize="28" fontWeight="700">₱</text>
        </g>
        {/* Separate palms, thumbs, and finger lines wrapping around the envelope */}
        <path d="M105 348c4-11 14-17 25-15l19 6-2 29-18 9c-10 5-22 0-26-10-3-7-2-13 2-19Z" fill="#C77B50" stroke="#245C46" strokeWidth="4"/>
        <path d="M259 348c-4-11-14-17-25-15l-19 6 2 29 18 9c10 5 22 0 26-10 3-7 2-13-2-19Z" fill="#C77B50" stroke="#245C46" strokeWidth="4"/>
        <path d="M112 346c8 1 16 5 23 11M108 354c8 1 15 5 23 11M252 346c-8 1-16 5-23 11M256 354c-8 1-15 5-23 11" fill="none" stroke="#9E5D43" strokeWidth="2.5" strokeLinecap="round"/>
        <path d="M141 340c-2 9-1 17 3 24M223 340c2 9 1 17-3 24" fill="none" stroke="#9E5D43" strokeWidth="3" strokeLinecap="round"/>
      </g>

      <g transform="translate(84 406) rotate(-8)">
        <rect width="122" height="83" rx="12" fill="#fff" stroke="#245C46" strokeWidth="4"/>
        <text x="15" y="26" fill="#245C46" fontSize="11" fontWeight="700">PAYSLIP</text>
        <path d="M15 39h91M15 50h64M15 61h82" stroke="#A8CDB7" strokeWidth="5" strokeLinecap="round"/>
        <text x="82" y="73" fill="#07965B" fontSize="18" fontWeight="700">₱</text>
      </g>
      <g fill="#F3BD4D" stroke="#9C6F1E" strokeWidth="3">
        <ellipse cx="130" cy="548" rx="38" ry="12"/><ellipse cx="130" cy="535" rx="38" ry="12"/><ellipse cx="130" cy="522" rx="38" ry="12"/>
      </g>
      <text x="514" y="327" fill="#245C46" fontSize="48" fontWeight="700">₱</text>
      <text x="271" y="103" fill="#07965B" fontSize="34" fontWeight="700">₱</text>
      <g transform="translate(492 544)">
        <circle cx="0" cy="0" r="13" fill="#fff"/><circle cx="22" cy="-4" r="13" fill="#fff"/><circle cx="10" cy="-20" r="13" fill="#fff"/><circle cx="-8" cy="-19" r="13" fill="#fff"/>
        <circle cx="7" cy="-10" r="8" fill="#F3BD4D"/>
        <path d="M8 3c1 20-5 30-18 39M8 3c8 16 17 24 29 26" stroke="#245C46" strokeWidth="4" fill="none"/>
      </g>
    </g>
  </svg>
}

function EmployeePortal({ employee, logout }: { employee: Employee; logout: () => void }) {
  const [page, setPage] = useState<EmployeePage>('Overview')
  const [profileOpen, setProfileOpen] = useState(false)
  const [notice, setNotice] = useState('')
  const [selectedSlip, setSelectedSlip] = useState<Payroll | null>(null)
  const [attendanceAction, setAttendanceAction] = useState<'in' | 'out' | null>(null)
  const [ownAttendance, setOwnAttendance] = useState<Attendance[]>([])
  const [payslips, setPayslips] = useState<Payroll[]>([])
  const pages: EmployeePage[] = ['Overview', 'Attendance', 'My Payslips', 'My Profile']
  const todayRecord = ownAttendance.find(a => a.date === todayISO())

  const reload = async () => {
    try {
      const [rows, slips] = await Promise.all([api.myAttendance(), api.myPayslips()])
      setOwnAttendance(rows); setPayslips(slips)
    } catch (e) { setNotice(errorMessage(e)) }
  }
  useEffect(() => { void reload() }, [])
  useEffect(() => {
    if (!notice) return
    const timer = window.setTimeout(() => setNotice(''), 3200)
    return () => window.clearTimeout(timer)
  }, [notice])

  // The server stamps the time (so the browser clock cannot be used to fake attendance).
  const recordAttendance = async (action: 'in' | 'out') => {
    try {
      const record = action === 'in' ? await api.timeIn() : await api.timeOut()
      setNotice(`Time ${action === 'in' ? 'In' : 'Out'} recorded successfully at ${fmtTime(action === 'in' ? record.timeIn : record.timeOut)}.`)
    } catch (e) { setNotice(errorMessage(e)) }
    await reload()
  }

  return <main className="employee-shell">
    <header className="topbar employee-topbar">
      <button className="brand" onClick={() => setPage('Overview')}>
        <span className="brand-mark"><img src="/assets/petsa-de-sweldo-logo.png" alt="Petsa de Sweldo logo"/></span>
        <span><b>Petsa de Sweldo</b><small>Employee Portal</small></span>
      </button>
      <nav>{pages.map(item => <button key={item} onClick={() => setPage(item)} className={page === item ? 'active' : ''}>{item}</button>)}</nav>
      <div className="admin">
        <button className="admin-profile" onClick={() => setProfileOpen(open => !open)} aria-expanded={profileOpen} aria-haspopup="menu">
          <span className="avatar">{employee.name.split(' ').map(part => part[0]).slice(0, 2).join('')}</span>
          <span><b>{employee.name.split(' ')[0]} {employee.name.split(' ').at(-1)}</b><small>{employee.position}</small></span>
        </button>
        {profileOpen && <div className="profile-menu" role="menu">
          <div><b>{employee.name}</b><small>{employee.id} · Employee</small></div>
          <button role="menuitem" onClick={logout}><Icon name="logout" size={16}/> Log out</button>
        </div>}
      </div>
    </header>
    <div className="employee-content">
      {page === 'Overview' && <EmployeeOverview employee={employee} todayRecord={todayRecord} attendance={ownAttendance} payslips={payslips} viewSlip={setSelectedSlip}/>}
      {page === 'Attendance' && <EmployeeAttendance employee={employee} rows={ownAttendance} todayRecord={todayRecord} record={setAttendanceAction}/>}
      {page === 'My Payslips' && <EmployeePayslips employee={employee} rows={payslips} viewSlip={setSelectedSlip}/>}
      {page === 'My Profile' && <EmployeeProfile employee={employee}/>}
    </div>
    {notice && <div className="toast">{notice}</div>}
    {selectedSlip && <EmployeePayslipModal employee={employee} row={selectedSlip} close={() => setSelectedSlip(null)}/>}
    {attendanceAction && <div className="modal-backdrop">
      <div className="modal delete-modal" role="alertdialog" aria-modal="true" aria-labelledby="attendance-confirm-title">
        <span className="status-dialog-icon"><Icon name="clock" size={21}/></span>
        <div id="attendance-confirm-title" className="card-title">Time {attendanceAction === 'in' ? 'In' : 'Out'}?</div>
        <div className="delete-copy">Are you sure you want to Time {attendanceAction === 'in' ? 'In' : 'Out'}?</div>
        <div className="modal-actions">
          <Button kind="secondary" onClick={() => setAttendanceAction(null)}>Cancel</Button>
          <Button onClick={() => { const action = attendanceAction; setAttendanceAction(null); void recordAttendance(action) }}>Confirm</Button>
        </div>
      </div>
    </div>}
  </main>
}

function EmployeePageHeader({ title, sub, children }: { title: string; sub: string; children?: React.ReactNode }) {
  return <div className="page-header"><Title sub={sub}>{title}</Title>{children}</div>
}

function nextPayday() {
  const now = new Date()
  const date = now.getDate()
  const target = new Date(now.getFullYear(), now.getMonth() + (date > 30 ? 1 : 0), date <= 15 ? 15 : 30)
  return fmtDate(target)
}

function EmployeeOverview({ employee, todayRecord, attendance, payslips, viewSlip }: { employee: Employee; todayRecord?: Attendance; attendance: Attendance[]; payslips: Payroll[]; viewSlip: (p: Payroll) => void }) {
  const [currentTime, setCurrentTime] = useState(new Date())
  useEffect(() => {
    const timer = window.setInterval(() => setCurrentTime(new Date()), 60000)
    return () => window.clearInterval(timer)
  }, [])
  const latest = payslips[0]
  const schedule = employeeSchedule(employee)
  const todayMetrics = todayRecord ? metrics(todayRecord, schedule) : { hours: 0, overtime: 0 }
  const firstName = employee.name.split(' ')[0]
  const hour = currentTime.getHours()
  const greeting = hour >= 5 && hour < 12 ? 'Good morning' : hour >= 12 && hour < 18 ? 'Good afternoon' : 'Good evening'
  return <div className="dashboard employee-page">
    <div className="welcome-row"><Title>{greeting}, {firstName}!</Title><div className="date-chip">{fmtDate(currentTime)}</div></div>
    <div className="hero-grid">
      <article className="card payroll-hero employee-attendance-hero"><div className="eyebrow">TODAY’S ATTENDANCE <span>{fmtDate(currentTime)}</span></div><div className="hero-label">Current Status</div><div className="employee-attendance-status">{attendanceStatus(todayRecord, employee) || '—'}</div><div className="hero-meta"><span><small>Actual Time In</small><b>{fmtTime(todayRecord?.timeIn || '')}</b></span><span><small>Actual Time Out</small><b>{fmtTime(todayRecord?.timeOut || '')}</b></span><span><small>Hours Worked</small><b>{todayRecord?.timeOut ? `${todayMetrics.hours.toFixed(2)} hrs` : '—'}</b></span></div><div className="employee-hero-note">Scheduled {scheduleShift(employee)} · {punctuality(todayRecord, employee)}</div><div className="pattern"/></article>
      <article className="card attendance-summary employee-payroll-summary"><div><div className="eyebrow">LATEST PAYROLL</div><div className="summary-title">Your salary at a glance</div><div className="summary-date">{latest?.period || 'No finalized payroll'}</div></div><div className="summary-stats"><div><b>{latest ? peso.format(latest.net) : '—'}</b><span>Net Pay</span></div><div><b>{latest ? 'Finalized' : 'Unavailable'}</b><span>Status</span></div><div><b>{nextPayday()}</b><span>Next Payday</span></div></div></article>
    </div>
    <section className="card employee-section employee-dashboard-schedule"><div className="section-heading"><div><div className="card-title">My Work Schedule</div><div className="muted">Your assigned working hours</div></div></div><ScheduleDetails employee={employee}/></section>
    <div className="lower-grid employee-lower-grid">
      <article className="card mini-card"><div className="card-head"><div><div className="card-title">Recent Attendance</div><div className="muted">Your latest recorded workdays</div></div><span className="soft-icon"><Icon name="clock"/></span></div><div className="employee-record-list">{attendance.slice(0,3).map(row => <div key={row.id}><span><b>{fmtDate(row.date)}</b><small>{fmtTime(row.timeIn)} – {fmtTime(row.timeOut)}</small></span><strong>{row.timeOut ? `${metrics(row, schedule).hours.toFixed(2)} hrs` : 'In progress'}</strong></div>)}</div></article>
      <article className="card mini-card"><div className="card-head"><div><div className="card-title">Latest Payslip</div><div className="muted">{latest?.period || 'No payroll record'}</div></div><span className="soft-icon"><Icon name="money"/></span></div>{latest ? <><div className="compact-slip"><Line label="Basic Pay" value={latest.basic}/><Line label="Overtime Pay" value={latest.overtime}/><Line label="Net Salary" value={latest.net} total/></div><Button kind="secondary" onClick={() => viewSlip(latest)}>View Payslip</Button></> : <Empty text="No finalized payslip available."/>}</article>
    </div>
  </div>
}

function ScheduleDetails({ employee }: { employee: Employee }) {
  const schedule = employeeSchedule(employee)
  return <div className="schedule-details"><Info label="Employment Type" value={employee.employmentType || 'Full-Time'}/><Info label="Shift Start" value={fmtTime(schedule.shiftStart)}/><Info label="Shift End" value={fmtTime(schedule.shiftEnd)}/><Info label="Assigned Workdays" value={scheduleWorkdays(employee)}/><Info label="Break Duration" value={scheduleBreak(employee)}/></div>
}

function TodayStrip({ record, employee }: { record?: Attendance; employee: Employee }) {
  const m = record ? metrics(record, employeeSchedule(employee)) : { hours: 0, overtime: 0 }
  return <div className="today-strip"><Info label="Attendance Status" value={attendanceStatus(record, employee) || '—'}/><Info label="Actual Time In" value={fmtTime(record?.timeIn || '')}/><Info label="Actual Time Out" value={fmtTime(record?.timeOut || '')}/><Info label="Hours Worked" value={`${m.hours.toFixed(2)} hrs`}/><Info label="Punctuality" value={punctuality(record, employee)}/></div>
}

function AttendanceTable({ rows, employee }: { rows: Attendance[]; employee: Employee }) {
  return <Table headers={['Date', 'Scheduled Shift', 'Actual Time In', 'Actual Time Out', 'Hours Worked', 'Status', 'Punctuality']}>
    {rows.map(row => { const m = metrics(row, employeeSchedule(employee)); return <tr key={row.id}><td>{fmtDate(row.date)}</td><td>{scheduleShift(employee)}</td><td>{fmtTime(row.timeIn)}</td><td>{fmtTime(row.timeOut)}</td><td>{row.timeOut ? `${m.hours.toFixed(2)} hrs` : '—'}</td><td><span className={`status attendance-status ${(attendanceStatus(row, employee, row.date) || 'absent').toLowerCase()}`}>{attendanceStatus(row, employee, row.date) || '—'}</span></td><td>{punctuality(row, employee)}</td></tr> })}
  </Table>
}

function EmployeeAttendance({ employee, rows, todayRecord, record }: { employee: Employee; rows: Attendance[]; todayRecord?: Attendance; record: (a: 'in' | 'out') => void }) {
  const [from, setFrom] = useState(''), [to, setTo] = useState('')
  const filtered = rows.filter(row => (!from || row.date >= from) && (!to || row.date <= to))
  return <div className="employee-page">
    <EmployeePageHeader title="My Attendance" sub=""><div className="attendance-primary-actions">
      <Button kind="secondary" disabled={Boolean(todayRecord?.timeIn)} onClick={() => record('in')}><Icon name="clock" size={17}/> Time In</Button>
      <Button disabled={!todayRecord?.timeIn || Boolean(todayRecord?.timeOut)} onClick={() => record('out')}><Icon name="clock" size={17}/> Time Out</Button>
    </div></EmployeePageHeader>
    <section className="card employee-section"><div className="section-heading"><div><div className="card-title">Today's Attendance</div><div className="muted">Scheduled hours and actual attendance are shown separately</div></div></div><ScheduleDetails employee={employee}/><TodayStrip record={todayRecord} employee={employee}/></section>
    <section className="card employee-section attendance-history"><div className="section-heading"><div><div className="card-title">Attendance History</div><div className="muted">Filter your recorded workdays</div></div><div className="date-filters"><label>From<DatePicker value={from} onChange={setFrom} label="Select start date"/></label><label>To<DatePicker value={to} onChange={setTo} label="Select end date"/></label></div></div><AttendanceTable rows={filtered} employee={employee}/>{!filtered.length && <Empty text="No attendance records in this date range."/>}</section>
  </div>
}

function EmployeePayslips({ employee, rows, viewSlip }: { employee: Employee; rows: Payroll[]; viewSlip: (p: Payroll) => void }) {
  const [selectedPeriod, setSelectedPeriod] = useState('All periods')
  const filtered = selectedPeriod === 'All periods' ? rows : rows.filter(row => row.period === selectedPeriod)
  return <div className="employee-page"><EmployeePageHeader title="My Payslips" sub=""><Select value={selectedPeriod} onChange={e => setSelectedPeriod(e.target.value)}><option>All periods</option>{rows.map(row => <option key={row.id}>{row.period}</option>)}</Select></EmployeePageHeader>
    <section className="card table-card"><Table headers={['Payroll Period', 'Basic Pay', 'Overtime Pay', 'Net Salary', 'Actions']}>{filtered.map(row => <tr key={row.id}><td><b>{row.period}</b></td><td><MoneyCell value={row.basic}/></td><td><MoneyCell value={row.overtime}/></td><td><MoneyCell value={row.net} strong/></td><td><div className="payslip-row-actions"><Button kind="ghost" onClick={() => viewSlip(row)}>View Payslip</Button><Button kind="ghost" onClick={() => { viewSlip(row); window.setTimeout(() => window.print(), 100) }}><Icon name="print" size={14}/> Print</Button></div></td></tr>)}</Table>{!filtered.length && <Empty text={`No finalized payslips are available for ${employee.name}.`}/>}</section>
  </div>
}

function EmployeeProfile({ employee }: { employee: Employee }) {
  const fields = [['Employee ID', employee.id], ['Full Name', employee.name], ['Department', employee.department], ['Position', employee.position], ['Employment Type', employee.employmentType || 'Full-Time']]
  return <div className="employee-page"><EmployeePageHeader title="My Profile" sub=""/><section className="card profile-card"><div className="profile-intro"><span className="profile-avatar">{employee.name.split(' ').map(part => part[0]).slice(0, 2).join('')}</span><div><b>{employee.name}</b><span>{employee.position} · {employee.department}</span></div></div><div className="profile-fields">{fields.map(([label, value]) => <label key={label}>{label}<Input value={value} readOnly/></label>)}</div><div className="profile-note">Employment details are maintained by your administrator. Contact HR if any information needs to be updated.</div></section></div>
}

function EmployeePayslipModal({ employee, row, close }: { employee: Employee; row: Payroll; close: () => void }) {
  return <div className="modal-backdrop"><div className="employee-slip-modal" id="employee-payslip"><div className="payslip-head"><div className="payslip-document-brand"><img src="/assets/petsa-de-sweldo-logo.png" alt="Petsa de Sweldo logo"/><div><div className="payslip-brand">Petsa de Sweldo</div><span>Official Payroll Statement</span></div></div><div><b>PAYSLIP</b><span>{row.period}</span></div></div><div className="employee-info"><Info label="Employee ID" value={employee.id}/><Info label="Employee Name" value={employee.name}/><Info label="Department" value={employee.department}/><Info label="Position" value={employee.position}/></div><div className="slip-section"><Line label="Basic Pay" value={row.basic}/><Line label="Overtime Pay" value={row.overtime}/><Line label="Gross Pay" value={row.gross} strong/><Line label="Net Salary" value={row.net} total/></div><div className="slip-footer"><span>Payroll ID: {row.id}</span><span>Finalized payroll record</span></div><div className="modal-actions no-print"><Button kind="secondary" onClick={close}>Close</Button><Button onClick={() => window.print()}><Icon name="print" size={15}/> Print Payslip</Button></div></div></div>
}

const financeNav: FinancePage[] = ['Dashboard', 'Payroll Management', 'Approval & Release']

function FinancePortal({ logout }: { logout: () => void }) {
  const [page, setPage] = useState<FinancePage>('Dashboard')
  const [employees, setEmployees] = useState<Employee[]>([])
  const [payrollRows, setPayrollRows] = useState<FinancePayroll[]>([])
  const [currentPeriod, setCurrentPeriod] = useState('')
  const [activity, setActivity] = useState<AuditEntry[]>([])
  const [notice, setNotice] = useState('')
  const [confirmAction, setConfirmAction] = useState<{ type: 'approve' | 'release'; row: FinancePayroll } | null>(null)
  const [detail, setDetail] = useState<FinancePayroll | null>(null)
  const [profileOpen, setProfileOpen] = useState(false)
  useEffect(() => {
    if (!notice) return
    const timer = window.setTimeout(() => setNotice(''), 3200)
    return () => window.clearTimeout(timer)
  }, [notice])

  // Opening any page recalculates the Pending payroll from the latest attendance (Approved/Released rows never change)
  const reload = async () => {
    try {
      const [snapshot, emps, audit] = await Promise.all([api.refreshPayroll(), api.employees(), api.audit()])
      setPayrollRows(snapshot.rows); setCurrentPeriod(snapshot.currentPeriod); setEmployees(emps); setActivity(audit)
    } catch (e) { setNotice(errorMessage(e)) }
  }
  useEffect(() => { void reload() }, [page])

  const updatePayroll = async () => {
    if (!confirmAction) return
    const { type, row } = confirmAction
    setConfirmAction(null)
    try {
      const saved = type === 'approve' ? await api.approvePayroll(row.id) : await api.releasePayroll(row.id)
      setPayrollRows(rows => rows.map(item => item.id === saved.id ? saved : item))
      setNotice(type === 'approve' ? 'Payroll approved successfully.' : 'Payroll released successfully.')
      setActivity(await api.audit())
    } catch (e) { setNotice(errorMessage(e)); void reload() }
  }

  return <main className="finance-shell finance-no-sidebar">
    <section className="finance-main">
      <header className="topbar finance-horizontal-header">
        <button className="brand" onClick={() => setPage('Dashboard')}>
          <span className="brand-mark"><img src="/assets/petsa-de-sweldo-logo.png" alt="Petsa de Sweldo logo"/></span>
          <span><b>Petsa de Sweldo</b><small>Finance Admin</small></span>
        </button>
        <nav aria-label="Finance navigation">{financeNav.map(item => <button key={item} className={page === item ? 'active' : ''} onClick={() => setPage(item)}>{item}</button>)}</nav>
        <div className="admin">
          <button className="admin-profile" onClick={() => setProfileOpen(open => !open)} aria-expanded={profileOpen} aria-haspopup="menu"><span className="avatar">FA</span><span><b>Finance Admin</b><small>Payroll & Finance</small></span></button>
          {profileOpen && <div className="profile-menu" role="menu"><div><b>Finance Admin</b><small>FIN-001 · Finance</small></div><button role="menuitem" onClick={logout}><Icon name="logout" size={16}/> Log out</button></div>}
        </div>
      </header>
      <div className="finance-content">
        {page === 'Dashboard' && <FinanceDashboard employees={employees} rows={payrollRows} currentPeriod={currentPeriod} activity={activity} setPage={setPage}/>}
        {page === 'Payroll Management' && <FinanceManagementHub employees={employees} rows={payrollRows} currentPeriod={currentPeriod} setDetail={setDetail} notify={setNotice}/>}
        {page === 'Approval & Release' && <ApprovalRelease rows={payrollRows} setDetail={setDetail} confirm={setConfirmAction}/>}
      </div>
    </section>
    {notice && <div className="toast">{notice}</div>}
    {detail && <FinancePayrollDetail row={detail} employee={employees.find(employee => employee.id === detail.employeeId)} close={() => setDetail(null)}/>}
    {confirmAction && <div className="modal-backdrop"><div className="modal delete-modal" role="alertdialog" aria-modal="true"><span className="status-dialog-icon"><Icon name="money" size={21}/></span><div className="card-title">{confirmAction.type === 'approve' ? 'Approve Payroll?' : 'Release Payroll?'}</div><div className="delete-copy">{confirmAction.type === 'approve' ? <>Approve payroll <b>{confirmAction.row.id}</b> for {confirmAction.row.name}? The payroll can then be released.</> : <>Release the approved payroll for <b>{confirmAction.row.name}</b>? No actual funds will be transferred.</>}</div><div className="modal-actions"><Button kind="secondary" onClick={() => setConfirmAction(null)}>Cancel</Button><Button onClick={() => void updatePayroll()}>{confirmAction.type === 'approve' ? 'Approve Payroll' : 'Release Payroll'}</Button></div></div></div>}
  </main>
}

function FinancePageHeader({ title, sub, actions }: { title: string; sub: string; actions?: React.ReactNode }) {
  return <div className="page-header"><Title sub={sub}>{title}</Title>{actions}</div>
}

function FinanceDashboard({ employees, rows: allRows, currentPeriod, activity, setPage }: { employees: Employee[]; rows: FinancePayroll[]; currentPeriod: string; activity: AuditEntry[]; setPage: (page: FinancePage) => void }) {
  const period = currentPeriod
  const rows = allRows.filter(row => row.period === currentPeriod)
  const hour = new Date().getHours()
  const greeting = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'
  const activeEmployees = employees.filter(employee => (employee.status || 'Active') === 'Active')
  const pending = rows.filter(row => row.status === 'Pending')
  const approved = rows.filter(row => row.status === 'Approved')
  const released = rows.filter(row => row.status === 'Released')
  const deductions = rows.reduce((sum, row) => sum + row.deductions, 0)
  const net = rows.reduce((sum, row) => sum + row.net, 0)
  const overtime = rows.reduce((sum, row) => sum + financeOvertime(row), 0)
  const basic = Math.max(0, rows.reduce((sum, row) => sum + row.gross, 0) - overtime)
  return <div className="dashboard finance-system-dashboard">
    <div className="welcome-row"><Title>{greeting}, Finance Admin</Title><div className="date-chip">{fmtDate()}</div></div>
    <div className="hero-grid">
      <article className="card payroll-hero">
        <div className="eyebrow">PAYROLL OVERVIEW <span>Current period</span></div>
        <div className="hero-label">Total Net Payroll</div>
        <div className="salary">{peso.format(net)}</div>
        <div className="hero-meta"><span><small>Pay period</small><b>{period}</b></span><span><small>Employees</small><b>{activeEmployees.length} active</b></span><span><small>Pending approval</small><b className="positive">{rows.filter(row => row.status === 'Pending').length} records</b></span></div>
        <Button onClick={() => setPage('Payroll Management')}>Process payroll <Icon name="chevron" size={16}/></Button>
        <div className="pattern"/>
      </article>
      <article className="card attendance-summary finance-approval-summary">
        <div><div className="eyebrow">PAYROLL APPROVAL STATUS</div><div className="summary-title">Current processing stage</div><div className="summary-date">{period}</div></div>
        <div className="summary-stats"><div><b>{pending.length}</b><span>Pending</span></div><div><b>{approved.length}</b><span>Approved</span></div><div><b>{released.length}</b><span>Released</span></div></div>
      </article>
    </div>
    <div className="lower-grid">
      <article className="card mini-card">
        <div className="card-head"><div><div className="card-title">Payroll Breakdown</div><div className="muted">{period}</div></div><span className="soft-icon"><Icon name="money"/></span></div>
        <Bar label="Basic Pay" value={basic} max={basic + overtime || 1}/><Bar label="Overtime Pay" value={overtime} max={basic + overtime || 1}/><Bar label="Other Deductions" value={deductions} max={basic + overtime || 1} danger/>
      </article>
      <article className="card mini-card">
        <div className="card-head"><div><div className="card-title">Pending Approvals</div><div className="muted">Payroll records requiring review</div></div><span className="soft-icon"><Icon name="clock"/></span></div>
        <div className="finance-pending-list">{pending.map(row => <button key={row.id} onClick={() => setPage('Approval & Release')}><span><b>{row.name}</b><small>{row.id} · {row.period}</small></span><strong>{peso.format(row.net)}</strong><Icon name="chevron" size={15}/></button>)}</div>
        {!pending.length && <div className="finance-empty-note">No payroll records are awaiting approval.</div>}
      </article>
      <article className="card mini-card">
        <div className="card-head"><div><div className="card-title">Recent Finance Activity</div><div className="muted">Latest payroll records</div></div><span className="soft-icon"><Icon name="activity"/></span></div>
        <div className="activity-list">{activity.slice(0, 3).map((item, i) => <Activity key={i} icon={item.action.includes('Released') ? 'activity' : 'money'} title={item.action} detail={item.details} time={item.date.slice(0, 8)}/>)}{!activity.length && <div className="finance-empty-note">No payroll activity yet.</div>}</div>
      </article>
    </div>
  </div>
}

function FinanceManagementHub({ employees, rows, currentPeriod, setDetail, notify }: { employees: Employee[]; rows: FinancePayroll[]; currentPeriod: string; setDetail: (row: FinancePayroll) => void; notify: (message: string) => void }) {
  type Tab = 'Salary Information' | 'Payroll Calculations' | 'Finance Reports'
  const [tab, setTab] = useState<Tab>('Salary Information')
  const [q, setQ] = useState('')
  const tabs: Tab[] = ['Salary Information', 'Payroll Calculations', 'Finance Reports']
  return <div>
    <FinancePageHeader title="Payroll Management" sub="" actions={tab !== 'Finance Reports' ? <Search value={q} onChange={setQ} placeholder="Search records"/> : undefined}/>
    <div className="finance-tabs">{tabs.map(item => <button key={item} className={tab === item ? 'active' : ''} onClick={() => setTab(item)}>{item}</button>)}</div>
    {tab === 'Salary Information' && <section className="card table-card"><Table headers={['Employee ID','Employee Name','Employment Type','Work Schedule','Monthly Salary']}>{employees.filter(employee => `${employee.id} ${employee.name}`.toLowerCase().includes(q.toLowerCase())).map(employee => <tr key={employee.id}><td><span className="id-badge">{employee.id}</span></td><td><b>{employee.name}</b><small className="cell-sub">{employee.department} · {employee.position}</small></td><td>{employee.employmentType || 'Full-Time'}</td><td><ScheduleCell employee={employee}/></td><td><MoneyCell value={employee.salary} strong/></td></tr>)}</Table></section>}
    {tab === 'Payroll Calculations' && <FinancePayrollTable rows={rows.filter(row => `${row.id} ${row.name}`.toLowerCase().includes(q.toLowerCase()))} action={setDetail} actionLabel="Review"/>}
    {tab === 'Finance Reports' && <FinanceReportsPanel employees={employees} rows={rows} currentPeriod={currentPeriod} notify={notify}/>}
  </div>
}

function FinanceReportsPanel({ employees, rows, currentPeriod, notify }: { employees: Employee[]; rows: FinancePayroll[]; currentPeriod: string; notify: (message: string) => void }) {
  const period = currentPeriod
  type ReportScope = 'Overall' | 'By Department' | 'By Employee'
  const [scope, setScope] = useState<ReportScope>('Overall')
  const [department, setDepartment] = useState(employees[0]?.department || '')
  const [employeeId, setEmployeeId] = useState(employees[0]?.id || '')
  const [employeeSearch, setEmployeeSearch] = useState('')
  const [payPeriod, setPayPeriod] = useState(period)
  useEffect(() => { if (!payPeriod && period) setPayPeriod(period) }, [period, payPeriod])
  const [generated, setGenerated] = useState(false)
  const employee = (id: string) => employees.find(item => item.id === id)
  const departments = Array.from(new Set(employees.map(item => item.department))).sort()
  const payPeriods = Array.from(new Set([period, ...rows.map(row => row.period)]))
  const employeeOptions = employees.filter(item => item.name.toLowerCase().includes(employeeSearch.trim().toLowerCase()))
  const reportRows = rows.filter(row => row.period === payPeriod).filter(row => scope === 'Overall' || (scope === 'By Department' ? employee(row.employeeId)?.department === department : row.employeeId === employeeId))
  const gross = reportRows.reduce((sum, row) => sum + row.gross, 0)
  const deductions = reportRows.reduce((sum, row) => sum + row.deductions, 0)
  const net = reportRows.reduce((sum, row) => sum + row.net, 0)
  const scopeLabel = scope === 'Overall' ? 'Overall Payroll' : scope === 'By Department' ? department : employee(employeeId)?.name || 'Employee'
  const resetPreview = () => setGenerated(false)
  useEffect(() => { if (!department && departments.length) setDepartment(departments[0]) }, [departments.length])
  const exportCsv = () => {
    const header = ['Payroll ID','Employee ID','Employee','Department','Pay Period','Gross Pay','Deductions','Net Pay','Status']
    const lines = reportRows.map(row => [row.id,row.employeeId,row.name,employee(row.employeeId)?.department || '',row.period,row.gross,row.deductions,row.net,row.status])
    const csv = [header,...lines].map(line => line.map(value => `"${String(value).replaceAll('"','""')}"`).join(',')).join('\n')
    const url = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
    const fileScope = scopeLabel.toLowerCase().replaceAll(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '')
    const link = document.createElement('a')
    link.href = url
    link.download = `petsa-de-sweldo-${fileScope}-${payPeriod.replaceAll('/','-')}.csv`
    document.body.appendChild(link)
    link.click()
    link.remove()
    window.setTimeout(() => URL.revokeObjectURL(url), 0)
    notify('CSV report exported.')
  }
  return <div className="report-generator-layout">
    <aside className="report-generator-controls">
      <div><div className="card-title">Generate Finance Report</div><div className="muted">Choose a report scope and pay period.</div></div>
      <label>Report Type<Select value={scope} onChange={event => { const nextScope = event.target.value as ReportScope; setScope(nextScope); if (nextScope === 'By Employee') { setEmployeeSearch(''); setEmployeeId('') }; resetPreview() }}><option>Overall</option><option>By Department</option><option>By Employee</option></Select></label>
      {scope === 'By Department' && <label>Department<Select value={department} onChange={event => { setDepartment(event.target.value); resetPreview() }}>{departments.map(item => <option key={item}>{item}</option>)}</Select></label>}
      {scope === 'By Employee' && <label>Search Employee Name<Input value={employeeSearch} onChange={event => { const value = event.target.value; setEmployeeSearch(value); const firstMatch = employees.find(item => item.name.toLowerCase().includes(value.trim().toLowerCase())); setEmployeeId(value.trim() ? firstMatch?.id || '' : ''); resetPreview() }} placeholder="Enter employee name"/>{employeeSearch && employeeOptions.length > 0 && employee(employeeId) && <span className="field-hint">Report for: {employee(employeeId)?.name} · {employeeId}</span>}{employeeSearch && !employeeOptions.length && <span className="field-hint error">No employee name found.</span>}</label>}
      <label>Pay Period<Select value={payPeriod} onChange={event => { setPayPeriod(event.target.value); resetPreview() }}>{payPeriods.map(item => <option key={item}>{item}</option>)}</Select></label>
      <Button disabled={scope === 'By Employee' && !employeeId} onClick={() => { setGenerated(true); notify('Finance report generated.') }}>Generate Report</Button>
    </aside>
    <section className="report-preview-shell">
      {!generated ? <div className="report-preview-empty"><Icon name="activity" size={30}/><b>Select a report type and pay period</b><span>Generate a report to preview payroll details.</span></div> : <div className="finance-report-document" id="finance-report-document">
        <div className="report-document-head"><div className="report-document-brand"><img src="/assets/petsa-de-sweldo-logo.png" alt="Petsa de Sweldo logo"/><div><div className="payslip-brand">Petsa de Sweldo</div><span>Finance Payroll Report</span></div></div><div><b>{scopeLabel}</b><span>Pay Period: {payPeriod}</span></div></div>
        <div className="report-document-meta"><Info label="Report Scope" value={scopeLabel}/><Info label="Date Generated" value={fmtDate()}/><Info label="Payroll Records" value={String(reportRows.length)}/><Info label="Prepared By" value="Finance Admin"/></div>
        <div className="report-document-summary"><div><span>Gross Pay</span><b>{peso.format(gross)}</b></div><div><span>Total Deductions</span><b>{peso.format(deductions)}</b></div><div><span>Net Pay</span><b>{peso.format(net)}</b></div></div>
        <div className="report-document-table"><Table headers={['Employee','Department','Gross Pay','Deductions','Net Pay','Status']}>{reportRows.map(row => <tr key={row.id}><td><b>{row.name}</b><small className="cell-sub">{row.employeeId}</small></td><td>{employee(row.employeeId)?.department}</td><td><MoneyCell value={row.gross}/></td><td><MoneyCell value={row.deductions}/></td><td><MoneyCell value={row.net} strong/></td><td><FinanceStatus value={row.status}/></td></tr>)}</Table>{!reportRows.length && <Empty text="No payroll data exists for this report selection."/>}</div>
        <div className="report-document-footer"><span>Generated from current payroll records</span><div><Button kind="secondary" disabled={!reportRows.length} onClick={() => { notify('Opening the printable PDF report.'); window.setTimeout(() => window.print(), 50) }}><Icon name="print" size={15}/> Export PDF</Button><Button disabled={!reportRows.length} onClick={exportCsv}><Icon name="activity" size={15}/> Export CSV</Button></div></div>
      </div>}
    </section>
  </div>
}

function ApprovalRelease({ rows, setDetail, confirm }: { rows: FinancePayroll[]; setDetail: (row: FinancePayroll) => void; confirm: (value: { type: 'approve' | 'release'; row: FinancePayroll }) => void }) {
  const [filter, setFilter] = useState('All')
  const filtered = rows.filter(row => filter === 'All' || row.status === filter)
  return <div className="approval-release-page"><FinancePageHeader title="Payroll Approval and Release" sub="" actions={<Select className="field approval-filter" value={filter} onChange={event => setFilter(event.target.value)}><option>All</option><option>Pending</option><option>Approved</option><option>Released</option></Select>}/><section className="card table-card"><Table headers={['Payroll ID','Employee','Period','Net Pay','Status','Actions']}>{filtered.map(row => <tr key={row.id}><td><span className="id-badge">{row.id}</span></td><td><b>{row.name}</b><small className="cell-sub">{row.employeeId}</small></td><td>{row.period}</td><td><MoneyCell value={row.net} strong/></td><td><FinanceStatus value={row.status}/></td><td><div className="payslip-row-actions"><Button kind="ghost" onClick={() => setDetail(row)}>Review</Button>{row.status === 'Pending' && <Button kind="secondary" onClick={() => confirm({ type: 'approve', row })}>Approve</Button>}{row.status === 'Approved' && <Button onClick={() => confirm({ type: 'release', row })}>Release</Button>}</div></td></tr>)}</Table></section><section className="card finance-history"><div className="card-title">Approval and Release History</div>{rows.filter(row => row.status !== 'Pending').map(row => <Activity key={row.id} icon={row.status === 'Released' ? 'activity' : 'money'} title={`Payroll ${row.status.toLowerCase()}`} detail={`${row.id} · ${row.name}`} time={row.updated}/>)}</section></div>
}

function FinancePayrollTable({ rows, action, actionLabel }: { rows: FinancePayroll[]; action: (row: FinancePayroll) => void; actionLabel: string }) {
  return <section className="card table-card"><Table headers={['Payroll ID','Employee','Period','Overtime Pay','Gross Pay','Deductions','Net Pay','Status','Action']}>{rows.map(row => <tr key={row.id}><td><span className="id-badge">{row.id}</span></td><td><b>{row.name}</b></td><td>{row.period}</td><td><MoneyCell value={financeOvertime(row)}/></td><td><MoneyCell value={row.gross}/></td><td><MoneyCell value={row.deductions}/></td><td><MoneyCell value={row.net} strong/></td><td><FinanceStatus value={row.status}/></td><td><Button kind="ghost" onClick={() => action(row)}>{actionLabel}</Button></td></tr>)}</Table></section>
}

function FinanceStatus({ value }: { value: FinancePayroll['status'] }) {
  return <span className={`finance-status ${value.toLowerCase()}`}>{value}</span>
}

function FinancePayrollDetail({ row, employee, close }: { row: FinancePayroll; employee?: Employee; close: () => void }) {
  return <div className="modal-backdrop"><div className="modal payroll-modal"><div className="modal-head"><div><div className="card-title">Payroll Review</div><div className="muted">{row.id} · {row.period}</div></div><Button kind="ghost" onClick={close}><Icon name="close"/></Button></div><div className="detail-grid"><Info label="Employee" value={row.name}/><Info label="Employee ID" value={row.employeeId}/><Info label="Employment Type" value={employee?.employmentType || 'Full-Time'}/><Info label="Scheduled Shift" value={scheduleShift(employee)}/><Info label="Assigned Workdays" value={scheduleWorkdays(employee)}/><Info label="Break Duration" value={scheduleBreak(employee)}/></div><Line label="Gross Pay" value={row.gross}/><Line label="Deductions" value={-row.deductions}/><Line label="Net Pay" value={row.net} total/><div className="review-status"><span>Current status</span><FinanceStatus value={row.status}/></div><div className="modal-actions"><Button onClick={close}>Close Review</Button></div></div></div>
}

function SystemAdminDashboard({ employees, history, setPage }: { employees: Employee[]; history: AuditEntry[]; setPage: (page: Page) => void }) {
  const active = employees.filter(employee => (employee.accountStatus || 'Active') === 'Active').length
  const inactive = employees.filter(employee => (employee.accountStatus || 'Active') === 'Inactive').length
  const locked = employees.filter(employee => employee.accountStatus === 'Locked')
  const activePercentage = employees.length ? Math.round(active / employees.length * 100) : 0
  const hour = new Date().getHours()
  const greeting = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'
  return <div className="dashboard system-dashboard">
    <div className="welcome-row"><Title>{greeting}, Administrator</Title><div className="date-chip">{fmtDate()}</div></div>
    <div className="hero-grid">
      <article className="card payroll-hero">
        <div className="eyebrow">ACCOUNT OVERVIEW <span>Current status</span></div>
        <div className="hero-label">Total Employee Accounts</div>
        <div className="salary">{employees.length}</div>
        <div className="hero-meta"><span><small>Active accounts</small><b>{active} enabled</b></span><span><small>Inactive accounts</small><b>{inactive} disabled</b></span><span><small>Security alerts</small><b className="positive">{locked.length} locked</b></span></div>
        <Button onClick={() => setPage('User Management')}>Manage accounts <Icon name="chevron" size={16}/></Button>
        <div className="pattern"/>
      </article>
      <article className="card attendance-summary">
        <div><div className="eyebrow">SYSTEM SECURITY</div><div className="summary-title">Account access at a glance</div><div className="summary-date">{fmtDate()}</div></div>
        <div className="summary-stats"><div><b>{active}</b><span>Active</span></div><div><b>{inactive}</b><span>Inactive</span></div><div><b>{locked.length}</b><span>Locked</span></div></div>
      </article>
    </div>
    <div className="lower-grid">
      <article className="card mini-card"><div className="card-head"><div><div className="card-title">Account Status</div><div className="muted">Employee system access</div></div><span className="soft-icon"><Icon name="users"/></span></div><div className="attendance-body"><div className="progress-ring" style={{ '--p': `${activePercentage * 3.6}deg` } as React.CSSProperties}><div><b>{activePercentage}%</b><span>active</span></div></div><div className="legend"><span><i className="dot green"/><b>{active}</b> Active</span><span><i className="dot yellow"/><b>{inactive}</b> Inactive</span><span><i className="dot gray"/><b>{locked.length}</b> Locked</span></div></div></article>
      <article className="card mini-card"><div className="card-head"><div><div className="card-title">Security Overview</div><div className="muted">Account access distribution</div></div><span className="soft-icon"><Icon name="clock"/></span></div><SystemBar label="Active Accounts" value={active} total={employees.length} tone="active"/><SystemBar label="Inactive Accounts" value={inactive} total={employees.length} tone="inactive"/><SystemBar label="Locked Accounts" value={locked.length} total={employees.length} tone="locked"/></article>
      <article className="card mini-card"><div className="card-head"><div><div className="card-title">Recent System Activity</div><div className="muted">Latest administration records</div></div><span className="soft-icon"><Icon name="activity"/></span></div><div className="activity-list">{history.slice(0, 3).map((item, i) => <Activity key={i} icon={item.action.includes('Created') ? 'users' : item.action.includes('Locked') || item.action.includes('Password') ? 'clock' : 'edit'} title={item.action} detail={`${item.target} · ${item.details}`} time={item.date.slice(0, 8)}/>)}{!history.length && <div className="muted">No system activity recorded yet.</div>}</div></article>
    </div>
  </div>
}

function SystemBar({ label, value, total, tone }: { label: string; value: number; total: number; tone: string }) {
  return <div className="system-bar-row"><div><span>{label}</span><b>{value}</b></div><div><i className={tone} style={{ width: `${total ? Math.max(value / total * 100, value ? 5 : 0) : 0}%` }}/></div></div>
}

function UserManagement({ employees, setEmployees, notify, onChanged }: { employees: Employee[]; setEmployees: React.Dispatch<React.SetStateAction<Employee[]>>; notify: (message: string) => void; onChanged: () => void }) {
  const [q, setQ] = useState('')
  const [department, setDepartment] = useState('All Departments')
  const [employeeModal, setEmployeeModal] = useState<Employee | 'new' | null>(null)
  const departments = Array.from(new Set(employees.map(employee => employee.department))).sort()
  const filtered = employees.filter(employee => `${employee.id} ${employee.name}`.toLowerCase().includes(q.toLowerCase())).filter(employee => department === 'All Departments' || employee.department === department)
  return <div>
    <div className="page-header"><Title sub={`${filtered.length} employee record${filtered.length === 1 ? '' : 's'}`}>User Management</Title><div className="header-actions"><Search value={q} onChange={setQ} placeholder="Search employee ID or name"/><Select value={department} onChange={event => setDepartment(event.target.value)}><option>All Departments</option>{departments.map(item => <option key={item}>{item}</option>)}</Select><Button onClick={() => setEmployeeModal('new')}><Icon name="plus" size={16}/> Add Employee</Button></div></div>
    <section className="card table-card system-user-table"><Table headers={['Employee ID','Full Name','Department','Position','Employment Type','Work Schedule','Actions']}>{filtered.map(employee => <tr key={employee.id}><td><span className="id-badge">{employee.id}</span></td><td><div className="person"><span>{employee.name.split(' ').map(part => part[0]).slice(0,2).join('')}</span><b>{employee.name}</b></div></td><td>{employee.department}</td><td>{employee.position}</td><td><span className="employment-type">{employee.employmentType || 'Full-Time'}</span></td><td><ScheduleCell employee={employee}/></td><td><Button kind="ghost" onClick={() => setEmployeeModal(employee)}><Icon name="edit" size={15}/> Edit Employee</Button></td></tr>)}</Table>{!filtered.length && <Empty text="No employees match your filters."/>}</section>
    {employeeModal && <SystemEmployeeModal value={employeeModal} employees={employees} close={() => setEmployeeModal(null)} save={async (employee, password) => { const saved = employeeModal === 'new' ? await api.createEmployee(employee, password) : await api.updateEmployee(employee, password); setEmployees(current => employeeModal === 'new' ? [...current, saved] : current.map(item => item.id === saved.id ? saved : item)); notify(employeeModal === 'new' ? 'Employee added successfully.' : 'Employee updated successfully.'); setEmployeeModal(null); onChanged() }}/>}
  </div>
}

function SystemEmployeeModal({ value, employees, close, save }: { value: Employee | 'new'; employees: Employee[]; close: () => void; save: (employee: Employee, password: string) => Promise<void> }) {
  const nextNumber = employees.reduce((max, e) => Math.max(max, Number(/^EMP-(\d+)$/.exec(e.id)?.[1] ?? 0)), 0) + 1
  const [saving, setSaving] = useState(false)
  const initial: Employee = value === 'new' ? { id: `EMP-${String(nextNumber).padStart(3,'0')}`, name: '', department: '', position: '', salary: 0, status: 'Active', accountStatus: 'Active', employmentType: 'Full-Time', schedule: fallbackSchedule } : { ...value, schedule: employeeSchedule(value) }
  const [form, setForm] = useState(initial)
  const [initialPassword, setInitialPassword] = useState('')
  const [error, setError] = useState('')
  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (!form.id.trim() || !form.name.trim() || !form.department.trim() || !form.position.trim()) return setError('Please complete all employee fields.')
    if (!Number.isFinite(form.salary) || form.salary <= 0) return setError('Enter a valid basic monthly salary.')
    if (!form.schedule?.workdays.length || !form.schedule.shiftStart || !form.schedule.shiftEnd) return setError('Complete the assigned work schedule.')
    if (minutes(form.schedule.shiftEnd) <= minutes(form.schedule.shiftStart)) return setError('Shift End must be later than Shift Start.')
    if (value === 'new' && initialPassword.length < 8) return setError('Initial password must contain at least 8 characters.')
    if (value === 'new' && employees.some(employee => employee.id === form.id)) return setError('Employee ID already exists.')
    if (value !== 'new' && initialPassword && initialPassword.length < 8) return setError('A new password must contain at least 8 characters.')
    setSaving(true); setError('')
    try { await save(form, initialPassword) } catch (err) { setError(errorMessage(err)); setSaving(false) }
  }
  const schedule = employeeSchedule(form)
  return <div className="modal-backdrop"><div className="modal schedule-editor-modal"><div className="modal-head"><div><div className="card-title">{value === 'new' ? 'Add Employee' : 'Edit Employee'}</div><div className="muted">Manage employee information, employment type, salary, and work schedule.</div></div><Button kind="ghost" onClick={close}><Icon name="close"/></Button></div><form onSubmit={submit}><label>Employee ID<Input value={form.id} disabled={value !== 'new'} onChange={event => setForm({ ...form, id: event.target.value.toUpperCase() })}/></label><label className="wide">Full Name<Input value={form.name} onChange={event => setForm({ ...form, name: event.target.value })}/></label><label>Department<Input value={form.department} onChange={event => setForm({ ...form, department: event.target.value })}/></label><label>Position<Input value={form.position} onChange={event => setForm({ ...form, position: event.target.value })}/></label><label>Employment Type<Select value={form.employmentType || 'Full-Time'} onChange={event => setForm({ ...form, employmentType: event.target.value as Employee['employmentType'] })}><option>Full-Time</option><option>Part-Time</option><option>Agency-Hired</option></Select></label><label>Basic Monthly Salary<Input type="number" min="1" step="0.01" value={form.salary || ''} onChange={event => setForm({ ...form, salary: Number(event.target.value) })} placeholder="Enter monthly salary"/></label><label>Shift Start<Input type="time" value={schedule.shiftStart} onChange={event => setForm({ ...form, schedule: { ...schedule, shiftStart: event.target.value } })}/></label><label>Shift End<Input type="time" value={schedule.shiftEnd} onChange={event => setForm({ ...form, schedule: { ...schedule, shiftEnd: event.target.value } })}/></label><WorkdayPicker value={schedule.workdays} onChange={workdays => setForm({ ...form, schedule: { ...schedule, workdays } })}/><label>Break Duration (minutes)<Input type="number" min="0" step="15" value={schedule.breakMinutes} onChange={event => setForm({ ...form, schedule: { ...schedule, breakMinutes: Math.max(0, Number(event.target.value)) } })}/></label>{value !== 'new' && <label className="wide">Account Status<Select value={form.accountStatus || 'Active'} onChange={event => setForm({ ...form, accountStatus: event.target.value as Employee['accountStatus'] })}><option>Active</option><option>Inactive</option>{form.accountStatus === 'Locked' && <option disabled>Locked</option>}</Select></label>}<label className="wide">{value === 'new' ? 'Initial Password' : 'New Password (leave blank to keep current)'}<Input type="password" value={initialPassword} onChange={event => setInitialPassword(event.target.value)} placeholder={value === 'new' ? 'Minimum 8 characters' : 'Leave blank to keep current'} autoComplete="new-password"/></label>{error && <div className="form-error">{error}</div>}<div className="modal-actions"><Button kind="secondary" type="button" onClick={close}>Cancel</Button><Button type="submit" disabled={saving}>{saving ? 'Saving…' : value === 'new' ? 'Add Employee' : 'Save Changes'}</Button></div></form></div></div>
}

function SystemHistory({ history }: { history: AuditEntry[] }) {
  const [actionFilter, setActionFilter] = useState('All Actions')
  const filtered = history.filter(item => actionFilter === 'All Actions' || item.action === actionFilter)
  return <div>
    <FinancePageHeader title="System History" sub="" actions={<Select className="field history-filter" value={actionFilter} onChange={event => setActionFilter(event.target.value)}><option>All Actions</option>{Array.from(new Set(history.map(item => item.action))).map(action => <option key={action}>{action}</option>)}</Select>}/>
    <section className="card system-audit"><div className="card-head"><div><div className="card-title">Activity History</div><div className="muted">{filtered.length} recorded system actions</div></div><span className="soft-icon"><Icon name="activity"/></span></div><Table headers={['Date & Time','Administrator','Action','Target','Details','Result']}>{filtered.map((item, i) => <tr key={`${item.at}-${i}`}><td>{item.date}</td><td>{item.actor}</td><td><b>{item.action}</b></td><td><span className="id-badge">{item.target}</span></td><td>{item.details}</td><td><span className={`status ${item.result === 'Attention' ? 'attention' : ''}`}>{item.result}</span></td></tr>)}</Table></section>
  </div>
}

function Bar({ label, value, max, danger }: { label: string; value: number; max: number; danger?: boolean }) {
  return <div className="bar-row"><div><span>{label}</span><b>{peso.format(value)}</b></div><div className="bar"><i className={danger ? 'danger' : ''} style={{ width: `${Math.max(4, value / max * 100)}%` }}/></div></div>
}
function Activity({ icon, title, detail, time }: { icon: string; title: string; detail: string; time: string }) {
  return <div className="activity"><span><Icon name={icon} size={16}/></span><div><b>{title}</b><small>{detail}</small></div><time>{time}</time></div>
}

function ScheduleCell({ employee }: { employee?: Employee }) {
  return <span className="schedule-cell"><b>{scheduleShift(employee)}</b><small>{scheduleWorkdays(employee)} · {scheduleBreak(employee)} break</small></span>
}

function WorkdayPicker({ value, onChange }: { value: Workday[]; onChange: (value: Workday[]) => void }) {
  return <fieldset className="workday-picker wide"><legend>Assigned Workdays</legend><div>{workdayOptions.map(day => <label key={day}><input type="checkbox" checked={value.includes(day)} onChange={() => onChange(value.includes(day) ? value.filter(item => item !== day) : workdayOptions.filter(item => [...value, day].includes(item)))}/><span>{day}</span></label>)}</div></fieldset>
}

function Table({ headers, children }: { headers: string[]; children: React.ReactNode }) {
  return <div className="table-scroll"><table><thead><tr>{headers.map(h => <th key={h}>{h}</th>)}</tr></thead><tbody>{children}</tbody></table></div>
}
function Empty({ text, action }: { text: string; action?: React.ReactNode }) { return <div className="empty"><Icon name="activity" size={28}/><b>{text}</b>{action}</div> }

function Info({ label, value }: { label: string; value: string }) { return <div className="info"><span>{label}</span><b>{value}</b></div> }
function Line({ label, value, strong, total }: { label: string; value: number; strong?: boolean; total?: boolean }) { return <div className={`money-line ${strong ? 'strong' : ''} ${total ? 'total' : ''}`}><span>{label}</span><b>{peso.format(value)}</b></div> }
