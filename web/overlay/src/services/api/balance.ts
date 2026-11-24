import { API_ORIGIN, request } from './client.js'

type BalanceInfo = {
  currency: string
  total_balance: string
  topped_up_balance: string
  granted_balance: string
}

type AccountBalance = {
  is_available: boolean
  balance_infos: BalanceInfo[]
}

const decimal = (value: unknown) => typeof value === 'string' && /^-?\d+(\.\d+)?$/.test(value)

const isBalanceInfo = (value: unknown): value is BalanceInfo =>
  typeof value === 'object' && value !== null &&
  'currency' in value && typeof value.currency === 'string' && value.currency.length > 0 &&
  'total_balance' in value && decimal(value.total_balance) &&
  'topped_up_balance' in value && decimal(value.topped_up_balance) &&
  'granted_balance' in value && decimal(value.granted_balance)

export async function getBalance(apiKey: string, signal: AbortSignal): Promise<AccountBalance> {
  const response = await request(`${API_ORIGIN}/user/balance`, {
    headers: { Authorization: `Bearer ${apiKey}` },
    cache: 'no-store',
    credentials: 'omit',
    redirect: 'error',
    signal,
  })
  const data: unknown = await response.json()
  if (typeof data !== 'object' || data === null ||
      !('is_available' in data) || typeof data.is_available !== 'boolean' ||
      !('balance_infos' in data) || !Array.isArray(data.balance_infos) ||
      !data.balance_infos.every(isBalanceInfo)) {
    throw new Error('Invalid balance response')
  }
  return { is_available: data.is_available, balance_infos: data.balance_infos }
}
