/** Reusable Amazon media workflow for the documented CUA tab API.
 * No account IDs, local paths, credentials, submission or legal acceptance.
 * Call one bounded step per tool invocation and inspect its returned state.
 */
const screenshots = 'img[alt^="screenshot"]';
const inFlight = new Map();

export async function assets(tab) {
  return tab.playwright.locator(screenshots).evaluateAll(nodes => nodes.map(node => ({
    alt: node.alt, src: node.src, width: node.naturalWidth, height: node.naturalHeight,
  })));
}

async function page(tab) {
  const snapshot = await tab.playwright.domSnapshot();
  if (!snapshot.includes('Step 3: Appstore details') || !snapshot.includes('Images and videos')) {
    throw new Error('Open Amazon Appstore details before running the media workflow.');
  }
  return snapshot;
}

async function waitDetached(tab, locator, retries = 10) {
  for (let attempt = 0; attempt < retries; attempt++) {
    try {
      await locator.waitFor({ state: 'detached', timeout: 10000 });
      await tab.playwright.domSnapshot();
      return true;
    } catch { /* CUA currently caps each individual wait; keep a bounded loop. */ }
  }
  return false;
}

async function clickDelete(tab, selector) {
  // The tiny SVG wrapper can fail locator actionability. Use fresh UI-backed
  // geometry and small scroll increments; never click outside the viewport.
  // Native activation can change the tab viewport. Activate/read before
  // measuring, rather than measuring a background tab and clicking after resize.
  await tab.getAXState({ emit: false });
  await tab.getScreenshot({ emit: false });
  for (let attempt = 0; attempt < 12; attempt++) {
    const position = await tab.playwright.evaluate(input => {
      const node = document.querySelector(input.selector);
      return node ? { rect: node.getBoundingClientRect().toJSON(), width: innerWidth, height: innerHeight, scrollY } : null;
    }, { selector, attempt });
    if (!position) throw new Error('Screenshot control was removed; inspect current state.');
    const rect = position.rect;
    if (rect.y >= 100 && rect.bottom <= position.height - 20 && rect.x >= 0 && rect.right <= position.width) {
      await tab.click([Math.round(rect.x + rect.width / 2), Math.round(rect.y + rect.height / 2)]);
      return;
    }
    await tab.scroll([Math.round(position.width * .6), Math.round(position.height * .65)], rect.y < 100 ? 'up' : 'down', .25);
    await tab.playwright.domSnapshot();
  }
  throw new Error('Could not bring screenshot control into the viewport.');
}

export async function selectLocale(tab, locale) {
  if ((await status(tab)).busy) return { status: 'processing' };
  await tab.playwright.getByRole('combobox').nth(1).fill(locale);
  const options = await tab.playwright.domSnapshot();
  if (!options.includes(locale)) throw new Error('Requested locale was not offered.');
  await tab.playwright.getByRole('combobox').nth(1).press('Enter');
  const selected = await tab.playwright.domSnapshot();
  if (!selected.includes(`- generic: ${locale}`)) throw new Error('Locale selection not confirmed.');
  return assets(tab);
}

export async function removeBatch(tab, limit = 2) {
  // Small batches keep each CUA invocation below a minute.
  if (limit < 1 || limit > 2) throw new Error('Use batches of one or two images.');
  let deleted = 0;
  for (let index = 0; index < limit; index++) {
    if ((await status(tab)).busy) return { status: 'processing', deleted };
    const state = await page(tab);
    if (state.includes('Deleting..') || state.includes('Uploading...')) {
      return { status: 'processing', deleted, remaining: (await assets(tab)).length };
    }
    // Read the current DOM: cached getAttribute/first locators can retain old IDs.
    const current = await assets(tab);
    if (!current.length) break;
    const alt = current[0].alt;
    if (!/^screenshot[ _]amzn1\.dex\.asset\.[a-z0-9]+$/.test(alt)) {
      throw new Error('Unexpected screenshot identifier; inspect the page.');
    }
    const selector = `div:has(> div > img[alt="${alt}"]) > div[style*="cursor: pointer"]`;
    await clickDelete(tab, selector);
    const confirmation = await tab.playwright.domSnapshot();
    if (!confirmation.includes('Are you sure you want to delete the image?')) {
      throw new Error('Expected image-only confirmation was not displayed.');
    }
    await tab.playwright.getByRole('button', { name: 'Confirm', exact: true }).click();
    await tab.playwright.domSnapshot();
    if (!await waitDetached(tab, tab.playwright.getByRole('img', { name: alt, exact: true }))) {
      return { status: 'processing', deleted, remaining: (await assets(tab)).length };
    }
    deleted++;
  }
  return { status: 'ready', deleted, remaining: (await assets(tab)).length };
}

export async function uploadGroup(tab, group, files) {
  if (!['tablet', 'firetv'].includes(group) || files.length !== 3) {
    throw new Error('Provide tablet/firetv and exactly three ordered screenshot paths.');
  }
  const state = await page(tab);
  if ((await status(tab)).busy) return { status: 'processing', count: (await assets(tab)).length };
  if (state.includes('Uploading...') || state.includes('Deleting..')) {
    return { status: 'processing', count: (await assets(tab)).length };
  }
  const current = await assets(tab);
  const relevant = current.filter(item => group === 'tablet' ? item.alt.startsWith('screenshot ') : item.alt.startsWith('screenshot_'));
  if (relevant.length) throw new Error('This screenshot group is not empty; do not upload duplicates.');
  const chooserPromise = tab.playwright.waitForEvent('filechooser', { timeout: 10000 });
  await tab.playwright.getByRole('button', { name: 'Choose File', exact: true }).nth(group === 'tablet' ? 0 : 3).click();
  await (await chooserPromise).setFiles(files);
  inFlight.set(tab.id, group);
  await tab.playwright.domSnapshot();
  return { status: 'upload-started', group };
}

export async function status(tab) {
  const snapshot = await page(tab);
  const current = await assets(tab);
  const pending = inFlight.get(tab.id);
  if (pending && current.filter(item => pending === 'tablet' ? item.alt.startsWith('screenshot ') : item.alt.startsWith('screenshot_')).length === 3) {
    inFlight.delete(tab.id);
  }
  return {
    busy: inFlight.has(tab.id) || snapshot.includes('Uploading...') || snapshot.includes('Deleting..'),
    tablet: current.filter(item => item.alt.startsWith('screenshot ')).length,
    firetv: current.filter(item => item.alt.startsWith('screenshot_')).length,
    assets: current,
  };
}
