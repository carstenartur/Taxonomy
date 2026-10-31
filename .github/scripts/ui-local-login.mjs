import { expect } from '@playwright/test';

const languages = [
  {
    code: 'de', link: 'Deutsch', title: 'Anmelden', username: 'Benutzername',
    password: 'Passwort', skip: 'Zum Anmeldeformular springen', navigation: 'Sprache',
    error: 'Benutzername oder Passwort ist ungültig.', logout: 'Sie wurden abgemeldet.'
  },
  {
    code: 'en', link: 'English', title: 'Sign in', username: 'Username',
    password: 'Password', skip: 'Skip to sign-in form', navigation: 'Language',
    error: 'Invalid username or password.', logout: 'You have been signed out.'
  }
];

export async function signInWithKeyboard(page, username, password,
    destination = url => !url.pathname.endsWith('/login')) {
  const usernameInput = page.locator('input[name="username"]');
  const passwordInput = page.locator('input[name="password"]');
  await usernameInput.focus();
  await usernameInput.fill(username);
  await passwordInput.focus();
  await passwordInput.fill(password);
  await Promise.all([
    page.waitForURL(destination, { timeout: 30_000 }),
    page.keyboard.press('Enter')
  ]);
}

async function expectLocale(page, baseUrl, language) {
  await expect(page.locator('html')).toHaveAttribute('lang', language.code);
  const cookie = (await page.context().cookies(baseUrl)).find(item => item.name === 'lang');
  expect(cookie?.value, 'Selected locale must survive the redirect in its cookie')
    .toBe(language.code);
}

async function expectVisibleFocus(control) {
  await expect(control).toBeFocused();
  await expect(control).toBeVisible();
  const indicator = await control.evaluate(element => {
    const style = getComputedStyle(element);
    return (style.outlineStyle !== 'none' && Number.parseFloat(style.outlineWidth) > 0)
      || style.boxShadow !== 'none';
  });
  expect(indicator, 'Keyboard focus needs a visible outline or shadow').toBe(true);
}

/** Runs inside the existing UI scenario, against real server-rendered forms and redirects. */
export async function verifyAnonymousLocalLogin(page, {
  baseUrl, username, password, javaScriptEnabled, passed
}) {
  const root = baseUrl.replace(/\/+$/, '');
  const loginPath = new URL(`${root}/login`).pathname;
  const applicationPath = new URL(`${root}/`).pathname;

  for (const language of languages) {
    // A protected request first exercises Spring's actual saved-request/session path.
    await page.goto(`${root}/?loginAcceptance=${language.code}`, { waitUntil: 'networkidle' });
    await expect(page).toHaveURL(url => url.pathname === loginPath);
    await page.getByRole('link', { name: language.link, exact: true }).click();
    await expect(page).toHaveURL(url => url.pathname === loginPath
      && url.searchParams.get('lang') === language.code);
    await expectLocale(page, root, language);
    await expect(page.getByRole('heading', { level: 1, name: language.title, exact: true }))
      .toBeVisible();
    await expect(page.getByRole('navigation', { name: language.navigation, exact: true }))
      .toBeVisible();

    const user = page.getByLabel(language.username, { exact: true });
    const secret = page.getByLabel(language.password, { exact: true });
    const submit = page.getByRole('button', { name: language.title, exact: true });
    await expect(user).toHaveAccessibleName(language.username);
    await expect(secret).toHaveAccessibleName(language.password);
    await expect(secret).toHaveAttribute('type', 'password');
    await expect(page.locator('#loginForm')).toHaveAttribute('action', loginPath);
    await expect(page.locator('#loginForm input[name="_csrf"]')).toHaveValue(/\S+/);

    // Start from the real autofocus, reach the skip link with the keyboard, then
    // follow its target and continue into the form without programmatic focus.
    await expect(user).toBeFocused();
    await page.keyboard.press('Shift+Tab');
    const skip = page.getByRole('link', { name: language.skip, exact: true });
    await expect(skip).toHaveAttribute('href', '#loginForm');
    await expect(page.locator('#loginForm')).toBeVisible();
    await expectVisibleFocus(skip);
    const skipBox = await skip.boundingBox();
    expect(skipBox?.width, 'Focused skip link must be visually revealed').toBeGreaterThan(20);
    expect(skipBox?.height, 'Focused skip link must be visually revealed').toBeGreaterThan(10);
    await page.keyboard.press('Enter');
    await expect(page).toHaveURL(url => url.hash === '#loginForm');
    await page.keyboard.press('Tab');
    await expectVisibleFocus(user);
    await page.keyboard.press('Tab');
    await expectVisibleFocus(secret);
    await page.keyboard.press('Tab');
    await expectVisibleFocus(submit);
    passed(`${language.code} anonymous labels, skip link and visible keyboard focus`);

    await signInWithKeyboard(page, username, `${password}-invalid`,
      url => url.pathname === loginPath && url.searchParams.has('error'));
    await expectLocale(page, root, language);
    await expect(page.getByRole('alert')).toHaveText(language.error);
    await expect(page.getByLabel(language.password, { exact: true })).toHaveValue('');
    passed(`${language.code} localized generic authentication error`);

    await signInWithKeyboard(page, username, password, url => url.pathname === applicationPath
      && url.searchParams.get('loginAcceptance') === language.code);
    await expectLocale(page, root, language);
    await expect(page.locator('#mainContent')).toBeVisible();
    const account = await page.request.get(`${root}/api/account/me`);
    expect(account.status(), 'The form login must authenticate the browser session').toBe(200);
    expect(await account.json()).toMatchObject({ username });
    if (!javaScriptEnabled) {
      expect(await page.evaluate(() => typeof window.TaxonomyI18n),
        'Application scripts must remain disabled in the no-JavaScript profile').toBe('undefined');
    }
    passed(`${language.code} keyboard login and saved request preserve locale`);

    // Use the framework's real CSRF-protected confirmation form, including when JS is off.
    await page.goto(`${root}/logout`, { waitUntil: 'networkidle' });
    const logout = page.locator('form');
    await expect(logout).toHaveAttribute('action', new URL(`${root}/logout`).pathname);
    await expect(logout.locator('input[name="_csrf"]')).toHaveValue(/\S+/);
    await logout.locator('button[type="submit"]').focus();
    await Promise.all([
      page.waitForURL(url => url.pathname === loginPath && url.searchParams.has('logout')),
      page.keyboard.press('Enter')
    ]);
    await expectLocale(page, root, language);
    await expect(page.getByRole('status')).toHaveText(language.logout);
    await page.goto(`${root}/`, { waitUntil: 'networkidle' });
    await expect(page).toHaveURL(url => url.pathname === loginPath);
    await expectLocale(page, root, language);
    passed(`${language.code} logout feedback, locale and protected-page denial`);
  }
}
