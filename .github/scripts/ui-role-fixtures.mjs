import { chromium, firefox, webkit, request } from '@playwright/test';

export const ROLE_ACCOUNTS = Object.freeze({
  USER: Object.freeze({
    username: 'qa-primary-user',
    password: 'qa-primary-user-password-2026',
    roles: ['USER']
  }),
  ARCHITECT: Object.freeze({
    username: 'qa-primary-architect',
    password: 'qa-primary-architect-password-2026',
    roles: ['USER', 'ARCHITECT']
  }),
  ADMIN: Object.freeze({
    username: 'admin',
    password: 'ui-primary-admin-password',
    roles: ['USER', 'ARCHITECT', 'ADMIN']
  })
});

export function basicAuthorization(username, password) {
  return `Basic ${Buffer.from(`${username}:${password}`, 'utf8').toString('base64')}`;
}

export async function provisionRoleAccounts({ baseUrl, adminUsername, adminPassword }) {
  const api = await request.newContext({
    baseURL: baseUrl,
    extraHTTPHeaders: {
      Authorization: basicAuthorization(adminUsername, adminPassword)
    }
  });
  try {
    for (const account of [ROLE_ACCOUNTS.USER, ROLE_ACCOUNTS.ARCHITECT]) {
      const response = await api.post('/api/admin/users', {
        data: {
          username: account.username,
          password: account.password,
          displayName: `QA ${account.roles.at(-1)}`,
          email: `${account.username}@example.invalid`,
          roles: account.roles
        }
      });
      if (![200, 201, 409].includes(response.status())) {
        throw new Error(`Unable to provision ${account.username}: ${response.status()} ${await response.text()}`);
      }
    }
  } finally {
    await api.dispose();
  }
}

export async function openRoleSession({
  baseUrl,
  role,
  browserName = 'chromium',
  viewport = { width: 1440, height: 1000 },
  adminUsername = 'admin',
  adminPassword = ROLE_ACCOUNTS.ADMIN.password,
  forcedColors = false,
  reducedMotion = true
}) {
  const browserType = { chromium, firefox, webkit }[browserName];
  if (!browserType) throw new Error(`Unsupported browser: ${browserName}`);
  if (!ROLE_ACCOUNTS[role]) throw new Error(`Unsupported role: ${role}`);

  await provisionRoleAccounts({ baseUrl, adminUsername, adminPassword });

  const account = role === 'ADMIN'
    ? { ...ROLE_ACCOUNTS.ADMIN, username: adminUsername, password: adminPassword }
    : ROLE_ACCOUNTS[role];
  const browser = await browserType.launch({ headless: true });
  const context = await browser.newContext({
    viewport,
    reducedMotion: reducedMotion ? 'reduce' : 'no-preference',
    forcedColors: forcedColors ? 'active' : 'none',
    acceptDownloads: true
  });
  const page = await context.newPage();

  await page.goto(`${baseUrl}/login`, { waitUntil: 'networkidle' });
  await page.locator('input[name="username"]').fill(account.username);
  await page.locator('input[name="password"]').fill(account.password);
  await Promise.all([
    page.waitForURL(url => !url.pathname.endsWith('/login'), { timeout: 30_000 }),
    page.keyboard.press('Enter')
  ]);
  await page.locator('#mainContent').waitFor({ state: 'visible', timeout: 60_000 });
  const onboardingDismiss = page.locator('#onboardingDismiss');
  if (await onboardingDismiss.isVisible().catch(() => false)) {
    await onboardingDismiss.focus();
    await page.keyboard.press('Enter');
  }
  await page.waitForFunction(() => Boolean(window.TaxonomyRoleSurface?.ready), null, { timeout: 20_000 });
  await page.evaluate(() => window.TaxonomyRoleSurface.ready);

  return { browser, context, page, account };
}

export async function csrfJson(page, endpoint, {
  method = 'POST',
  body = undefined
} = {}) {
  return page.evaluate(async ({ endpoint, method, body }) => {
    const token = document.querySelector('meta[name="_csrf"]')?.content;
    const header = document.querySelector('meta[name="_csrf_header"]')?.content || 'X-CSRF-TOKEN';
    const headers = { 'Content-Type': 'application/json' };
    if (token) headers[header] = token;
    const response = await fetch(endpoint, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await response.text();
    let json = null;
    try { json = text ? JSON.parse(text) : null; } catch { /* status and text remain evidence */ }
    return { status: response.status, text, json };
  }, { endpoint, method, body });
}

export async function navigateToPage(page, pageId) {
  const control = page.locator(`#mainNavTabs [data-page="${pageId}"]`);
  await control.scrollIntoViewIfNeeded();
  await control.click();
  await page.locator(`#tab-${pageId}`).waitFor({ state: 'visible', timeout: 20_000 });
}

export async function navigateArchitectureSubtab(page, subtab) {
  const aliases = {
    overview: 'arch-overview',
    relations: 'arch-relations',
    export: 'arch-export'
  };
  const actualSubtab = aliases[subtab] || subtab;
  await navigateToPage(page, 'architecture');
  const control = page.locator(`#architectureSubTabs [data-subtab="${actualSubtab}"]`);
  await control.scrollIntoViewIfNeeded();
  await control.click();
  await page.locator(`#subtab-${actualSubtab}`).waitFor({ state: 'visible', timeout: 20_000 });
}
