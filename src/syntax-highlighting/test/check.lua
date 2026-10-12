-- Checks src/syntax-highlighting/vim on sample.ktm: each probe is
-- {line, text on it, the highlight group its syntax group links to}.
local probes = {
  {1, "module", "Include"}, {1, "demo", "Identifier"},
  {2, "import", "Include"}, {2, "mem", "Identifier"}, {2, "str", "Identifier"}, {2, "geo", "Identifier"},
  {3, "#include", "Include"}, {3, "lib.ktm", "String"},
  {4, "LIMIT", "Macro"}, {5, "#ifdef", "PreCondit"},
  {6, "doc", "Comment"}, {6, "code", "SpecialComment"}, {6, "TODO", "Todo"},
  {7, "pub", "StorageClass"}, {7, "struct", "Structure"}, {7, "User", "Typedef"}, {7, "Base", "Type"},
  {7, "i64", "Type"}, {7, "String", "Type"},
  {8, "enum", "Structure"}, {8, "u8", "Type"}, {8, "0x1F_FF", "Number"},
  {9, "type", "Typedef"}, {9, "Ints", "Typedef"}, {9, "Vec", "Type"}, {9, "i64", "Type"},
  {10, "extern", "StorageClass"}, {10, "abs", "Function"},
  {11, "const", "StorageClass"}, {11, "RATIO", "Constant"}, {11, "1.5e3", "Float"},
  {12, "fn", "Keyword"}, {12, "pick", "Function"}, {12, "bool", "Type"}, {12, "null", "Constant"},
  {13, "older", "Function"}, {13, "...", "Operator"},
  {14, "ad", "Keyword"}, {14, "format", "Function"}, {14, "%d", "SpecialChar"}, {14, "%-5s", "SpecialChar"},
  {14, "%.2f", "SpecialChar"}, {14, "%Q", "SpecialChar"}, {14, "\\n", "SpecialChar"},
  {15, "defer", "Keyword"}, {15, "free", "Function"},
  {16, "'\\n'", "Character"},
  {17, "switch", "Conditional"}, {17, "case", "Label"}, {17, "...", "Operator"}, {17, "default", "Label"}, {17, "fallthrough", "Statement"},
  {18, "try", "Exception"}, {18, "throw", "Exception"}, {18, "red", "Constant"}, {18, "Error", "Type"},
  {19, "auto", "Type"}, {19, "1_000_000", "Number"}, {19, "0b1010", "Number"}, {19, ".5", "Float"},
  {20, "done", "Label"},
  {23, "age", ""}, {23, "'ab'", "Error"}, {23, "v =", ""}, {23, "abs", "Function"}, {23, "plain", "Function"},
  {21, "return", "Statement"}, {21, "self", "Special"}, {21, "sizeof", "Operator"}, {21, "T", "Type"}, {21, "LIMIT", "Constant"},
}
local fails = 0
for _, p in ipairs(probes) do
  local line = vim.fn.getline(p[1])
  local col = string.find(line, p[2], 1, true)
  local raw0 = col and vim.fn.synIDattr(vim.fn.synID(p[1], col, 1), "name") or ""
  local hl = raw0 ~= "" and vim.api.nvim_get_hl(0, {name = raw0}) or {}
  local got = col and (hl.link or raw0) or "<not found>"
  local raw = col and vim.fn.synIDattr(vim.fn.synID(p[1], col, 1), "name") or ""
  if got ~= p[3] then
    fails = fails + 1
    io.stdout:write(string.format("FAIL %d %-10s want %-14s got %s (%s)\n", p[1], p[2], p[3], got, raw))
  end
end
io.stdout:write(string.format("%d/%d probes ok\n", #probes - fails, #probes))
if fails > 0 then vim.cmd("cquit 1") end
