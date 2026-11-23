import { expect, test } from '@playwright/test'

test('landing page loads from the VeriPago subpath', async ({ page }) => {
  await page.goto('./')

  await expect(page).toHaveTitle(/VeriPago/)
  await expect(page.getByRole('heading', { name: /El pago llega\./ })).toBeVisible()
  await expect(page.getByText('DaviPlata', { exact: true }).first()).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Muestra el QR y cobra' })).toBeVisible()
})

test('login modal offers Google only for merchant and admin access', async ({ page }) => {
  await page.goto('./')
  await page.getByRole('button', { name: 'Empezar gratis' }).click()

  await expect(page.getByRole('heading', { name: 'Continúa con Google' })).toBeVisible()
  await expect(page.getByRole('button', { name: /Quiero comprar/ })).toHaveCount(0)
  await expect(page.locator('.login-modal .button-full')).toContainText('Continuar con Google')
  await expect(page.locator('.login-modal input[type="password"]')).toHaveCount(0)
})

test('unknown checkout links show a recoverable unavailable state', async ({ page }) => {
  await page.goto('enlace-inexistente')

  await expect(page.getByRole('heading', { name: 'Este enlace no está disponible' })).toBeVisible()
  const home = page.getByRole('link', { name: 'Conoce VeriPago' })
  await expect(home).toHaveAttribute('href', '/VeriPago')
  await home.click()
  await expect(page.getByRole('heading', { name: /El pago llega\./ })).toBeVisible()
})

test('landing page fits a narrow mobile viewport without horizontal overflow', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('./')
  await expect(page.getByRole('heading', { name: /El pago llega\./ })).toBeVisible()

  const overflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth)
  expect(overflow).toBe(false)
})
