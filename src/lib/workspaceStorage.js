// Drafts in a shared browser belong to the signed-in account as well.
let user = null;
export function setStorageUser(value) { user = value; }
export const workspaceStorage = {
  getItem(key) {
    const scoped = user ? `devprep.user.${user.username}.${key}` : key;
    let value = window.localStorage.getItem(scoped);
    // Existing local drafts are inherited only by the owner, never by a friend.
    if (value === null && user?.admin) {
      value = window.localStorage.getItem(key);
      if (value !== null) { window.localStorage.setItem(scoped, value); window.localStorage.removeItem(key); }
    }
    return value;
  },
  setItem(key, value) { window.localStorage.setItem(user ? `devprep.user.${user.username}.${key}` : key, value); },
  removeItem(key) { window.localStorage.removeItem(user ? `devprep.user.${user.username}.${key}` : key); },
};
