import assert from 'node:assert/strict';
import { uploadGroup, status, waitForUpload } from './amazon_media_ui.mjs';

for (const count of [3, 4]) {
  const images = [];
  let chosen, awaitedIndex;
  const tab = { id: `offline-${count}`, playwright: {
    domSnapshot: async () => 'Step 3: Appstore details Images and videos',
    locator: () => ({ evaluateAll: async () => images,
      nth: index => ({ waitFor: async () => { awaitedIndex=index; } }) }),
    waitForEvent: async () => ({ setFiles: async files => { chosen=files; } }),
    getByRole: () => ({ nth: () => ({ click: async () => {} }) }),
  } };
  const files = Array.from({ length: count }, (_, i) => `screen-${i+1}.png`);
  await uploadGroup(tab, 'firetv', files);
  assert.deepEqual(chosen, files);
  for (let i=0; i<count-1; i++) images.push({ alt: `screenshot_${i}` });
  assert.equal((await status(tab)).busy, true, 'must wait for the final screenshot');
  await waitForUpload(tab, 1);
  assert.equal(awaitedIndex, count-1);
  images.push({ alt: `screenshot_${count-1}` });
  assert.equal((await status(tab)).busy, false);
  assert.equal((await status(tab)).firetv, count);
}
console.log('Three/four-image upload completion checks passed.');
