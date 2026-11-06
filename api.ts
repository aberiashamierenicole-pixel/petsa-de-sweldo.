// Thin client for the Java API. Requests go to /api on the same origin (Vite proxies it to the backend in dev),
// so the HttpOnly session cookie is sent automatically and no token is ever stored in the browser.

export type Workday = 'Mon' | 'Tue' | 'Wed' | 'Thu' | 'Fri' | 'Sat' | 'Sun'
export type WorkSchedule = { shiftStart: string; shiftEnd: string; workdays: Workday[]; breakMinutes: number }
export type Employee = { id: string; name: string; department: string; position: string; salary: number; status?: 'Active' | 'Inactive'; accountStatus?: 'Active' | 'Inactive' | 'Locked'; employmentType?: 'Full-Time' | 'Part-Time' | 'Agency-Hired'; schedule?: WorkSchedule }
export type Attendance = { id: string; employeeId: string; date: string; timeIn: string; timeOut: string }
export type Payroll = { id: string; employeeId: string; period: string; days: number; basic: number; overtime: number; gross: number; net: number; processed: boolean }
export type FinancePayroll = { id: string; employeeId: string; name: string; period: string; overtime: number; gross: number; deductions: number; net: number; status: 'Pending' | 'Approved' | 'Released'; updated: string; basic?: number; days?: number }
export type AuditEntry = { date: string; at: string; actor: string; action: string; target: string; details: string; result: 'Success' | 'Attention' }
export type Me = { role: 'admin' | 'finance' | 'employee'; loginId: string; displayName: string; employee: Employee | null }
export type FinanceSnapshot = { currentPeriod: string; rows: FinancePayroll[] }

// The server's calendar day (Asia/Manila by default), sent on every response, so "today" never depends on the browser's clock/time zone.
let serverDate = ''
export const serverToday = () => serverDate

export class ApiError extends Error {
  status: number
  constructor(message: string, status: number) {
    super(message)
    this.status = status
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { 'X-Requested-With': 'PetsaDeSweldo' }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  let res: Response
  try {
    res = await fetch(`/api${path}`, { method, headers, credentials: 'same-origin', body: body === undefined ? undefined : JSON.stringify(body) })
  } catch {
    throw new ApiError('Cannot reach the server. Make sure the Java API is running.', 0)
  }
  const day = res.headers.get('X-Server-Date')
  if (day) serverDate = day
  if (res.status === 204) return undefined as T
  let data: unknown = null
  try { data = await res.json() } catch { /* non-JSON error page from a proxy */ }
  if (!res.ok) {
    const message = (data as { error?: string } | null)?.error
      || (res.status === 502 || res.status === 504 ? 'Cannot reach the server. Make sure the Java API is running.' : 'Request failed. Please try again.')
    // An expired session anywhere (except the login form itself) sends the user back to the login page.
    if (res.status === 401 && path !== '/auth/login') window.dispatchEvent(new Event('pds-unauthorized'))
    throw new ApiError(message, res.status)
  }
  return data as T
}

const employeeBody = (e: Employee, password: string) => ({
  id: e.id, name: e.name, department: e.department, position: e.position, salary: e.salary,
  employmentType: e.employmentType, schedule: e.schedule, accountStatus: e.accountStatus, password,
})

export const api = {
  login: (loginId: string, password: string) => request<Me>('POST', '/auth/login', { loginId, password }),
  logout: () => request<void>('POST', '/auth/logout'),
  me: () => request<Me>('GET', '/auth/me'),

  employees: () => request<Employee[]>('GET', '/employees'),
  createEmployee: (e: Employee, password: string) => request<Employee>('POST', '/employees', employeeBody({ ...e, accountStatus: undefined }, password)),
  updateEmployee: (e: Employee, password: string) => request<Employee>('PUT', `/employees/${encodeURIComponent(e.id)}`, employeeBody(e, password)),
  audit: () => request<AuditEntry[]>('GET', '/audit'),

  myAttendance: () => request<Attendance[]>('GET', '/me/attendance'),
  timeIn: () => request<Attendance>('POST', '/me/attendance/time-in'),
  timeOut: () => request<Attendance>('POST', '/me/attendance/time-out'),
  myPayslips: () => request<Payroll[]>('GET', '/me/payslips'),

  financePayroll: () => request<FinanceSnapshot>('GET', '/finance/payroll'),
  refreshPayroll: () => request<FinanceSnapshot>('POST', '/finance/payroll/refresh'),
  approvePayroll: (id: string) => request<FinancePayroll>('POST', `/finance/payroll/${encodeURIComponent(id)}/approve`),
  releasePayroll: (id: string) => request<FinancePayroll>('POST', `/finance/payroll/${encodeURIComponent(id)}/release`),
}
