// A foreground visit lasts until the next App.onShow. Page navigation keeps it.
// Keep this in memory so a new launch never inherits yesterday's dismissal.
let visit = 0
let homeAdVisit = 0

export function beginAppVisit(): void {
  visit++
}

export function currentAppVisit(): number {
  if (!visit) visit = 1
  return visit
}

export function claimHomeAd(expectedVisit: number): boolean {
  if (expectedVisit !== currentAppVisit() || homeAdVisit === expectedVisit) return false
  homeAdVisit = expectedVisit
  return true
}
