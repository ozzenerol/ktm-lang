" Vim syntax file
" Language:   ktm
" Filename:   *.ktm, *.ktmh
" Note:       This is a *standalone* syntax definition. It deliberately does
"             NOT inherit from the bundled C syntax. Every construct is
"             defined here so ktm keeps its own identity.
"
" Ordering matters: when a plain :syn-match and a :syn-region can start at the
" same column, the item defined LAST wins. So the greedy single-character
" operator/delimiter matches are defined first (lowest priority) and the
" things that must beat them (comments, strings, numbers) come afterwards.
" :syn-keyword items always outrank matches/regions regardless of order.

" ---------------------------------------------------------------------------
" Buffer-local options for ktm files
" ---------------------------------------------------------------------------
setlocal cindent
setlocal tabstop=4 softtabstop=0 expandtab shiftwidth=2 smarttab

" Keep the filetype as ktm so the LSP / format keymaps recognise it.
if &filetype !=# "ktm"
  setlocal filetype=ktm
endif

" Don't rebuild the syntax if it is already in place for this buffer.
if exists("b:current_syntax") && b:current_syntax ==# "ktm"
  finish
endif

syntax clear
syntax case match

" ---------------------------------------------------------------------------
" Operators & delimiters (lowest priority is defined first)
" ---------------------------------------------------------------------------
" syn match ktmOperator  "[-+*/%=<>!~&|^?:]" " I'm not a fan so commented out
" syn match ktmDelimiter "[(){}\[\];,.]"     " I also don't particularly like
                                            " coloured punctuation

" ---------------------------------------------------------------------------
" Numbers
" ---------------------------------------------------------------------------
syn match ktmNumber /\<0[xX]\x\+\>/
syn match ktmNumber /\<0[bB][01]\+\>/
syn match ktmNumber /\<\d\+\%([uU]\=[lL]\{0,2}\)\>/
syn match ktmFloat  /\<\d\+\.\d*\%([eE][-+]\=\d\+\)\=\>/
syn match ktmFloat  /\<\d\+[eE][-+]\=\d\+\>/
syn match ktmFloat  /\.\d\+\%([eE][-+]\=\d\+\)\=\>/

" ---------------------------------------------------------------------------
" Strings & character constants
" ---------------------------------------------------------------------------
syn match  ktmEscape /\\./ contained
" printf-style conversion specifiers (with ktm extras: b z q t)
syn match  ktmFormat /%\%(\d\+\$\)\=[-+' #0*]*\%(\d*\|\*\|\*\d\+\$\)\%(\.\%(\d*\|\*\|\*\d\+\$\)\)\=\%([hlLjzt]\|ll\|hh\)\=\%([aAbdiuoxXDOUfFeEgGcCsSpnqtz]\|\[\^\=.[^]]*\]\)/ contained
syn match  ktmFormat /%%/ contained
" ktm DolDoc command embedded in a string ($FG,2$ ... $FG$); literal $ is $$.
syn match  ktmDolDoc /\$[^$]*\$/ contained

syn region ktmString    start=+"+ skip=+\\\\\|\\"+ end=+"+ contains=ktmEscape,ktmFormat,ktmDolDoc
" ktm character constants may pack up to 8 bytes: 'A', '\n', 'TEXT'
syn match  ktmCharacter "'\%(\\.\|[^'\\]\)\{1,8}'" contains=ktmEscape

" ---------------------------------------------------------------------------
" Comments
" ---------------------------------------------------------------------------
syn keyword ktmTodo            contained TODO FIXME XXX NOTE HACK BUG
syn match   ktmCommentAnnotation /@\w\+/ contained
syn region  ktmCommentLine     start="//" skip="\\$" end="$" keepend contains=ktmTodo,ktmCommentAnnotation,@Spell
syn region  ktmComment         start="/\*" end="\*/" contains=ktmTodo,ktmCommentAnnotation,@Spell

" ---------------------------------------------------------------------------
" Preprocessor / compiler directives
" ---------------------------------------------------------------------------
syn match   ktmPreProc /^\s*#\s*\%(define\|undef\|ifdef\|ifndef\|ifaot\|ifjit\|if\|elifdef\|elif\|else\|endif\|exe\|help_index\|help_file\|assert\|error\|warning\|pragma\|import\|public\|extern\)\>/
syn keyword ktmPreProcDefined defined

syn region	ktmIncluded	display contained start=+"+ skip=+\\\\\|\\"+ end=+"+
syn match	ktmIncluded	display contained "<[^>]*>"
syn match	ktmInclude	display "^\s*\zs\%(%:\|#\)\s*include\>\s*["<]" contains=ktmIncluded
syn match	ktmInclude	display "^\s*\zs\%(%:\|#\)\s*link\>\s*["<]" contains=ktmIncluded

" ---------------------------------------------------------------------------
" Types & structures
" ---------------------------------------------------------------------------
syn keyword ktmType      U0 Bool I8 U8 I16 U16 I32 U32 I64 U64 F32 F64 auto
syn keyword ktmStructure class union

syntax match PascalType /\%(\( \|\t\|\n\|$\|<\|}\|(\)\{1}\)\@<=\<\u[[:alnum:]]*\>\( \|\t\|\n\|$\|>\)\%( \|=\|,\|!\|:\)\@!/ containedin=cStatement,cType,cStructure
highlight def link PascalType Type

" ---------------------------------------------------------------------------
" Storage classes / qualifiers
" ---------------------------------------------------------------------------
syn keyword ktmStorageClass extern _extern import _import public private static
syn keyword ktmStorageClass reg noreg interrupt haserrcode argpop noargpop
syn keyword ktmStorageClass nofink inline lastclass

" ---------------------------------------------------------------------------
" Statements / keywords
" ---------------------------------------------------------------------------
syn keyword ktmConditional if else switch case default start end
syn keyword ktmRepeat      while for do
syn keyword ktmStatement   return goto break continue
syn keyword ktmOperatorKw  sizeof alignof offset try catch throw
" Note: `asm` is intentionally NOT a keyword here a keyword match at `asm`
" would block the asm{} region below from ever starting. The region's
" matchgroup highlights `asm {` instead.

" ---------------------------------------------------------------------------
" Constants
" ---------------------------------------------------------------------------
syn keyword ktmBoolean  TRUE FALSE ON OFF
syn keyword ktmConstant NULL EXIT_FAILURE EXIT_SUCCESS EXIT_FAIL EXIT_OK
syn keyword ktmConstant I64_MIN I64_MAX I32_MIN I32_MAX I16_MIN I16_MAX
syn keyword ktmConstant I8_MIN I8_MAX U64_MAX U32_MAX U16_MAX U8_MAX
syn keyword ktmConstant STDIN STDOUT STDERR __BUFSIZ__

" ---------------------------------------------------------------------------
" Functions / scopes (matches outranked by the keywords above)
" ---------------------------------------------------------------------------
" An identifier immediately followed by '(' is a function (def or call).
"syn match ktmFunction /\<\h\w*\ze\s*(/
" Member / scope resolution: Name::
syn match ktmScope    /\<\h\w*\ze::/

" ---------------------------------------------------------------------------
" Inline assembly: asm { ... }
" ---------------------------------------------------------------------------
syn region ktmAsm matchgroup=ktmLabelKw start="\<asm\>\s*{" end="}" contains=ktmAsmLabel,ktmAsmFunction,ktmComment,ktmCommentLine,ktmNumber,ktmString,ktmCharacter
syn match  ktmAsmLabel    "@@\d\+" contained
syn match  ktmAsmFunction /^\s*\w\+::/ contained

" ---------------------------------------------------------------------------
" Highlight links
" ---------------------------------------------------------------------------
hi def link ktmComment           Comment
hi def link ktmCommentLine       Comment
hi def link ktmTodo              Todo
hi def link ktmCommentAnnotation Todo

hi def link ktmPreProc           PreProc
hi def link ktmPreProcDefined    PreProc

hi def link ktmType              Type
hi def link ktmStructure         Structure
hi def link ktmStorageClass      StorageClass

hi def link ktmConditional       Conditional
hi def link ktmRepeat            Repeat
hi def link ktmStatement         Statement
hi def link ktmOperatorKw        Statement
hi def link ktmLabelKw           Keyword

hi def link ktmBoolean           Boolean
hi def link ktmConstant          Constant

hi def link ktmNumber            Number
hi def link ktmFloat             Float

hi def link ktmString            String
hi def link ktmCharacter         Character
hi def link ktmEscape            SpecialChar
hi def link ktmFormat            Special
hi def link ktmDolDoc            PreProc

hi def link ktmFunction          Function
hi def link ktmScope             Identifier

hi def link ktmOperator          Operator
hi def link ktmDelimiter         Delimiter

hi def link ktmAsm               Special
hi def link ktmAsmLabel          Label
hi def link ktmAsmFunction       Function

hi def link ktmInclude           Include
hi def link ktmIncluded          ktmString



let b:current_syntax = "ktm"
