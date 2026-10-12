" Vim filetype plugin: ktm
if exists("b:did_ftplugin")
  finish
endif
let b:did_ftplugin = 1

setlocal commentstring=//\ %s
setlocal comments=sO:*\ -,mO:*\ \ ,exO:*/,s1:/*,mb:*,ex:*/,://
setlocal formatoptions-=t formatoptions+=croql
setlocal cindent cinoptions=:0,l1,g0,N-s,(0,u0,j1,J1
setlocal expandtab shiftwidth=4 softtabstop=4 tabstop=4
setlocal suffixesadd=.ktm
setlocal include=^\\s*\\(#\\s*include\\\|import\\)

let b:undo_ftplugin = "setlocal commentstring< comments< formatoptions< cindent< cinoptions<"
      \ . " expandtab< shiftwidth< softtabstop< tabstop< suffixesadd< include<"
