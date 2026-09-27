// Whether a server-supplied URL is safe to put in `src=`/`href=`.
//
// WHAT THIS IS FOR. A response URL reaches a DOM attribute in five places
// (PostScreen, FeedPostCard, PlaceThumbnail, and both anchors in
// DataAttribution), and each of them asks this function first (FE-603-T12,
// measured per place in safe-url-sites.test.tsx and post.test.tsx). The two
// anchors are the execution path: a browser will not run
// `<img src="javascript:…">`, but it does run `<a href="javascript:…">` on
// click. `rel="noreferrer noopener"` is a tabnabbing control and says nothing
// about the scheme.
//
// For a while this file existed and nothing imported it but its own test, so
// every rejection that test proves reached no page. A guard that exists and a
// guard that runs are different claims; the per-place tests are the second.
//
// The contract cannot carry this check: `format: uri` asks whether a string
// parses as a URI, not what scheme it names, so `javascript:alert(1)` and
// `data:text/html;…` both satisfy it — and the generated client narrows the
// field to `string`, dropping the format entirely.
//
// POLICY: `https:` ONLY, decided by the owner rather than inferred here.
// An earlier version of the test file left this open as `it.todo` because
// guessing it would have handed the next reader a decision nobody made. The
// answer, with the reasoning that produced it:
//
//   - all nine URLs the shipped fixtures carry are already https, so the
//     accept-side measurement does not weaken (verified against
//     packages/contracts/fixtures);
//   - it is the straightest reading of CLAUDE.md's "외부 URL은 allowlist";
//   - the accepted cost: if BE later serves an http image it will not render,
//     and someone has to trace the blank image back to this function. The
//     three image sites keep http out for different reasons, and only one of
//     them is a constraint on the field the site renders:
//       · PlaceThumbnail renders `media_assets.served_url`, which V010's
//         `media_assets_served_url_check` limits to https.
//       · The feed and post covers render `posts.cover_url`, whose only CHECK
//         is V015's non-blank one. A cover is https only because both writers
//         (CuratedPostImporter and PostAuthoringService, the callers of the
//         only two `INSERT INTO posts` that `git grep` finds under
//         apps/api/src/main) put the same URL into a media_assets row in the
//         same transaction, so V010 refuses an http cover there. V021's
//         published → cover_asset_id CHECK does not close the gap: it is NOT
//         VALID, so posts published before V021 are exempt, and it never
//         requires `cover_url` to equal the asset's `served_url`. An http
//         cover would come from such an old row or from a writer that stops
//         copying the asset URL.
//     A refused feed cover keeps an empty box of the cover's size, because
//     that cover is the card's only control into the post (FE-603-T13).
//
// That last line is why the cost is written down rather than just the rule.
//
// IMPLEMENTATION: the URL parser decides, not string matching. Measured before
// choosing (node, same parser the browser uses):
//
//   'javascript:alert(1)'        → protocol 'javascript:'
//   'JavaScript:alert(1)'        → protocol 'javascript:'   (case normalized)
//   '  javascript:alert(1)'      → protocol 'javascript:'   (leading ws stripped)
//   'java\tscript:alert(1)'      → protocol 'javascript:'   (tab removed)
//   'java\nscript:alert(1)'      → protocol 'javascript:'   (newline removed)
//
// So the evasions collapse on their own and there is nothing to strip by hand.
// A hand-rolled `replace(/[\x00-\x20]/g, '')` would work today too, but it
// re-implements a normalization the spec already defines — and the next
// evasion shape it does not know about is the one that gets through. This way
// the check follows the same parser the browser will use on the value.
//
// The three inputs the constructor REJECTS rather than parses — '',
// whitespace-only, and '//evil.example/a.jpg' — are all inputs this function
// must refuse anyway, so throwing is the correct answer and not a gap. The
// protocol-relative case is worth naming: it is not an execution path, but it
// silently loads from another origin using the page's own scheme.
export function isSafeUrl(value: string): boolean {
  let parsed: URL;
  try {
    // No base argument on purpose: a base would resolve '//evil.example/a.jpg'
    // and every relative path into a real URL, and the question here is
    // whether the SERVER sent something absolute and safe.
    parsed = new URL(value);
  } catch {
    // Not a parseable absolute URL — empty, whitespace, protocol-relative,
    // or malformed. None of them may reach an attribute.
    return false;
  }
  return parsed.protocol === 'https:';
}
