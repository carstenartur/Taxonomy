import test from './junit-callbacks.cjs';
export default test;
export { test };
export const before=test.before, after=test.after, beforeEach=test.beforeEach, afterEach=test.afterEach;
