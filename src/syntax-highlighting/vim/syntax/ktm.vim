" Vim syntax file
" Language:   ktm
" Files:      *.ktm, *.ktmh
" Notes:      Standalone: nothing is inherited from the C syntax. With
"             `ktm -lsp` attached, Neovim adds semantic highlighting on top
"             (modules, enum members, parameters, fields, the stdlib...).
"
" Order matters: of a :syn-match and a :syn-region that start at the same
" column the one defined LAST wins, so the broad matches come first and
" comments, strings and numbers after them. :syn-keyword beats both.

if exists("b:current_syntax")
  finish
endif

let s:cpo_save = &cpo
set cpo&vim

syntax case match

" ---------------------------------------------------------------------------
" Names by convention (lowest priority)
" ---------------------------------------------------------------------------
" A call or a definition: a name right before `(`
syn match ktmFunction /\<\h\w*\ze\s*(/
" A method written outside its struct: `i64 User.older(` / a call `u.older(`
syn match ktmMethod /\.\zs\h\w*\ze\s*(/
" Types are CamelCase (User, String, Vec), a type parameter one capital (T)
syn match ktmTypeName /\<\u\w*\l\w*\>/
syn match ktmTypeParam /\<\u\>/
" Constants and macros are ALL_CAPS (MAX_LINE, EAGAIN, IS_LINUX)
syn match ktmConstName /\<\u[0-9A-Z_]\+\>/
" `Kind.square`, `geo.Kind.square`: an enum member after a type
syn match ktmEnumMember /\%(\<\u\w*\l\w*\.\)\@<=\h\w*\>\ze\s*\%([^(.[:space:]]\|$\)/
" A module prefix: `geo.area`, `str.format` (lowercase name before `.` and
" a name) is left to the LSP: it can't be told from `p.x` by text.

" Generic arguments: `Vec<i64>`, `Pair<u8[], Entry>`, `map.Map<u8[], Entry>`
syn region ktmGeneric matchgroup=ktmGenericBracket start=/\%(\<\u\w*\)\@<=</ end=/>/ oneline transparent contains=ktmGeneric,ktmType,ktmTypeName,ktmTypeParam,ktmModulePath,ktmPointer

" ---------------------------------------------------------------------------
" Keywords
" ---------------------------------------------------------------------------
syn keyword ktmType        void bool auto
syn keyword ktmType        i8 u8 i16 u16 i32 u32 i64 u64 f32 f64
syn keyword ktmBuiltinType String Error
syn keyword ktmStructure   struct union enum
syn keyword ktmTypedef     type
syn keyword ktmFnKeyword   fn
syn keyword ktmModule      module
syn keyword ktmStorage     pub private static const extern _extern inline volatile reg noreg
syn keyword ktmConditional if else switch
syn keyword ktmLabel       case default
syn keyword ktmRepeat      while for do
syn keyword ktmStatement   return goto break continue fallthrough
syn keyword ktmException   try catch throw
syn keyword ktmDefer       defer
syn keyword ktmOperatorKw  sizeof alignof typeof
syn keyword ktmBoolean     true false
syn keyword ktmNull        null
syn keyword ktmSelf        self
" `ad` is an ordinary name except in front of a declaration
syn match   ktmDefer       /\<ad\>\ze\s\+\%(\h\w*\%(\.\h\w*\)*\%(<[^>]*>\)\=\s*\**\s*\)\h\w*\s*[=;]/
syn match   ktmDefer       /\<ad\>\ze\s\+\%(\h\w*\%(\.\h\w*\)*\%(<[^>]*>\)\=\s*\**\s*\)\h\w*\s*\[/

" `extern "c"`
syn match   ktmExternC     /\<extern\s\+"c"/ contains=ktmStorage

" `case 1 ... 5:` and `T ...rest`
syn match   ktmEllipsis    /\.\.\./

" ---------------------------------------------------------------------------
" Numbers: decimal, hex, binary, digit separators, floats
" ---------------------------------------------------------------------------
syn match ktmNumber /\<\d[0-9_]*\>/
syn match ktmNumber /\<0[xX]\x[0-9a-fA-F_]*\>/
syn match ktmNumber /\<0[bB][01][01_]*\>/
syn match ktmFloat  /\<\d[0-9_]*\.\d[0-9_]*\%([eE][-+]\=\d\+\)\=\>/
syn match ktmFloat  /\<\d[0-9_]*\.\ze\%([^.0-9]\|$\)/
syn match ktmFloat  /\<\d[0-9_]*[eE][-+]\=\d\+\>/
syn match ktmFloat  /\%(\w\)\@<!\.\d[0-9_]*\%([eE][-+]\=\d\+\)\=\>/

" ---------------------------------------------------------------------------
" Strings and characters
" ---------------------------------------------------------------------------
syn match  ktmEscape     /\\\%([ntrbfva\\'"d]\|x\x\{1,2}\|[0-7]\{1,3}\)/ contained
syn match  ktmBadEscape  /\\[^ntrbfva\\'"dx0-7]/ contained
" printf conversions, ktm's own (%Q %C %b %z) included
syn match  ktmFormat     /%\%(\d\+\$\)\=[-+ #0']*\%(\d\+\|\*\)\=\%(\.\%(\d\+\|\*\)\)\=\%(hh\|h\|ll\|l\|L\|j\|z\|t\)\=[diouxXeEfFgGaAcCsSpnqQbzt]/ contained
syn match  ktmFormat     /%%/ contained
syn region ktmString     start=+"+ skip=+\\\\\|\\"+ end=+"+ end=+$+ contains=ktmEscape,ktmBadEscape,ktmFormat,@Spell
syn match  ktmCharacter  /'\%(\\\%([ntrbfva\\'"d]\|x\x\{1,2}\|[0-7]\{1,3}\)\|[^'\\]\)'/ contains=ktmEscape
syn match  ktmBadChar    /'\%(\\.\|[^'\\]\)\{2,}'/

" ---------------------------------------------------------------------------
" Comments
" ---------------------------------------------------------------------------
syn keyword ktmTodo        contained TODO FIXME XXX NOTE HACK BUG SAFETY
syn match   ktmDocRef      /`[^`]\+`/ contained
syn region  ktmCommentLine start="//" end="$" keepend contains=ktmTodo,ktmDocRef,@Spell
syn region  ktmComment     start="/\*" end="\*/" contains=ktmTodo,ktmDocRef,@Spell fold extend
syn match   ktmCommentError /\*\// 

" ---------------------------------------------------------------------------
" Preprocessor
" ---------------------------------------------------------------------------
syn region  ktmPreCondit  start=/^\s*#\s*\%(if\|ifdef\|ifndef\|elif\|elifdef\|else\|endif\|ifjit\|ifaot\)\>/ skip=/\\$/ end=/$/ keepend contains=ktmComment,ktmCommentLine,ktmNumber,ktmDefined,ktmConstName
syn keyword ktmDefined    contained defined
syn region  ktmDefine     start=/^\s*#\s*\%(define\|undef\)\>/ skip=/\\$/ end=/$/ keepend contains=ktmMacroName,ktmComment,ktmCommentLine,ktmNumber,ktmFloat,ktmString,ktmCharacter
syn match   ktmMacroName  /\%(#\s*\%(define\|undef\)\s\+\)\@<=\h\w*/ contained
syn region  ktmIncluded   display contained start=+"+ skip=+\\\\\|\\"+ end=+"+
syn match   ktmIncluded   display contained /<[^>]*>/
syn match   ktmInclude    display /^\s*#\s*\%(include\|link\)\>\s*["<]/me=e-1 nextgroup=ktmIncluded skipwhite
syn match   ktmInclude    display /^\s*#\s*\%(include\|link\)\>/ nextgroup=ktmIncluded skipwhite
syn match   ktmPreProc    /^\s*#\s*\%(error\|warning\|pragma\|exe\|assert\)\>.*$/ contains=ktmString

" ---------------------------------------------------------------------------
" Declarations
" ---------------------------------------------------------------------------
" `module a.b;` and `import a, x = b.c;`: the module paths
syn match   ktmModulePath /\%(\<module\s\+\)\@<=\h\w*\%(\.\h\w*\)*/
" an import's list may span lines: every path in it, aliases plain
syn region  ktmImportList matchgroup=ktmModule start=/\<import\>/ end=/;/ keepend contains=ktmImportPath,ktmImportAlias,ktmComment,ktmCommentLine
syn match   ktmImportAlias /\h\w*\ze\s*=/ contained
syn match   ktmImportPath  /\%(=\s*\)\@<!\%(\h\w*\s*=\s*\)\@<!\<\h\w*\%(\.\h\w*\)*\ze\s*[,;]/ contained
syn match   ktmImportPath  /\%(=\s*\)\@<=\h\w*\%(\.\h\w*\)*/ contained
" `struct Name`, `enum Name`, `union Name`, `type Name =`
syn match   ktmTypeDecl   /\%(\<\%(struct\|union\|enum\)\s\+\)\@<=\h\w*/
syn match   ktmTypeDecl   /\%(\<type\s\+\)\@<=\h\w*\ze\s*=/
" A label: `done:` alone at the start of a line (not `case x:`)
syn match   ktmLabelName  /^\s*\zs\h\w*\ze\s*:\%([^:]\|$\)/ contains=NONE

" ---------------------------------------------------------------------------
" Inline assembly: asm { ... }
" ---------------------------------------------------------------------------
syn region ktmAsm matchgroup=ktmAsmKeyword start=/\<asm\>\s*{/ end=/}/ contains=ktmAsmLabel,ktmAsmFunction,ktmComment,ktmCommentLine,ktmNumber,ktmString,ktmCharacter
syn match  ktmAsmLabel    /@@\d\+/ contained
syn match  ktmAsmFunction /^\s*\w\+::/ contained

" ---------------------------------------------------------------------------
" Folding and syncing
" ---------------------------------------------------------------------------
syn region ktmBlock start=/{/ end=/}/ transparent fold
syn sync ccomment ktmComment minlines=50

" ---------------------------------------------------------------------------
" Links
" ---------------------------------------------------------------------------
hi def link ktmComment        Comment
hi def link ktmCommentLine    Comment
hi def link ktmCommentError   Error
hi def link ktmTodo           Todo
hi def link ktmDocRef         SpecialComment

hi def link ktmType           Type
hi def link ktmBuiltinType    Type
hi def link ktmTypeName       Type
hi def link ktmTypeParam      Type
hi def link ktmTypeDecl       Typedef
hi def link ktmGenericBracket Delimiter
hi def link ktmStructure      Structure
hi def link ktmTypedef        Typedef
hi def link ktmFnKeyword      Keyword
hi def link ktmModule         Include
hi def link ktmModulePath     Identifier
hi def link ktmImportPath     Identifier
hi def link ktmImportAlias    Identifier
hi def link ktmStorage        StorageClass
hi def link ktmExternC        StorageClass

hi def link ktmConditional    Conditional
hi def link ktmLabel          Label
hi def link ktmLabelName      Label
hi def link ktmRepeat         Repeat
hi def link ktmStatement      Statement
hi def link ktmException      Exception
hi def link ktmDefer          Keyword
hi def link ktmOperatorKw     Operator
hi def link ktmEllipsis       Operator

hi def link ktmBoolean        Boolean
hi def link ktmNull           Constant
hi def link ktmSelf           Special
hi def link ktmConstName      Constant
hi def link ktmEnumMember     Constant

hi def link ktmNumber         Number
hi def link ktmFloat          Float
hi def link ktmString         String
hi def link ktmCharacter      Character
hi def link ktmBadChar        Error
hi def link ktmEscape         SpecialChar
hi def link ktmBadEscape      Error
hi def link ktmFormat         SpecialChar

hi def link ktmFunction       Function
hi def link ktmMethod         Function

hi def link ktmPreCondit      PreCondit
hi def link ktmDefined        PreProc
hi def link ktmDefine         Macro
hi def link ktmMacroName      Macro
hi def link ktmInclude        Include
hi def link ktmIncluded       String
hi def link ktmPreProc        PreProc

hi def link ktmAsm            Special
hi def link ktmAsmKeyword     Keyword
hi def link ktmAsmLabel       Label
hi def link ktmAsmFunction    Function

let b:current_syntax = "ktm"

let &cpo = s:cpo_save
unlet s:cpo_save
