import { test } from "node:test"
import assert from "node:assert/strict"
import { parseSupportContact, hasSupportContact } from "../src/lib/support.ts"

test("nothing configured means no contact is shown", () => {
  assert.deepEqual(parseSupportContact(undefined, undefined), {})
  assert.equal(hasSupportContact(parseSupportContact("", "  ")), false)
})
test("valid values are kept, malformed ones are dropped", () => {
  assert.deepEqual(parseSupportContact("help@example.org", "+27 11 555 0100"), { email: "help@example.org", phone: "+27 11 555 0100" })
  assert.deepEqual(parseSupportContact("not an email", "abc"), {})
  assert.deepEqual(parseSupportContact("a@b", "12"), {})
})
test("values that could be markup or links are rejected", () => {
  assert.deepEqual(parseSupportContact("<script>@x.com", "+27<1>"), {})
})
