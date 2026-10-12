" ktm source files (`set`, not `setfiletype`: Vim and Neovim may already
" have guessed another language for .ktm)
autocmd BufRead,BufNewFile *.ktm,*.ktmh set filetype=ktm
