import { expect, test } from '@playwright/test'

test('landing page loads from the VeriPago subpath', async ({ page }) => {
  await page.goto('./')

  await expect(page).toHaveTitle(/VeriPago/)
  await expect(page.getByRole('heading', { name: /El pago llega\./ })).toBeVisible()
  await expect(page.getByRole('img', { name: 'DaviPlata' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Muestra el QR y cobra' })).toBeVisible()
  await expect(page.locator('.brand-lockup')).toHaveCSS('width', '58px')
  await expect(page.locator('.wallet-pills img')).toHaveCount(3)
  await expect(page.locator('.wallet-pills')).toHaveText('')
  await expect(page.locator('.wallet-pills img').first()).toHaveCSS('border-radius', '50%')
  await expect(page.locator('.wallet-pills img').first()).toHaveCSS('width', '34px')
  const walletMarksLoaded = await page.locator('.wallet-pills img').evaluateAll((images) =>
    images.every((image) => (image as HTMLImageElement).complete && (image as HTMLImageElement).naturalWidth > 0),
  )
  expect(walletMarksLoaded).toBe(true)
})

test('Google login is available without an intermediate modal', async ({ page }) => {
  await page.goto('./')

  await expect(page.locator('.login-modal')).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Continuar con Google/ }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: /Quiero comprar/ })).toHaveCount(0)
  await expect(page.locator('input[type="password"]')).toHaveCount(0)
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
