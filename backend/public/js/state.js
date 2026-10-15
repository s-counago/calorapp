// What every view needs to know: today (with the 03:00 cutoff, from the server), which
// features are on, and a way to ask the current view to redraw after a change.

export const state = {
  today: null,
  features: {},
  featureList: [],
  /** Set by the shell; redraws the current view without losing the scroll position. */
  refresh: () => {},
};

export const isOn = (key) => state.features[key] === true;
