# Identidade visual do Buzin Cast

Em 6 de novembro de 2026, Henrique aprovou aplicar ao aplicativo o mesmo padrão da ficha Amazon: fundo azul escuro com órbitas, bloco verde com B à esquerda, Buzin em branco e CAST em verde à direita, centralizados e ocupando a área útil. Preserve essa arte nas duas edições Fire OS e nas atualizações. O ícone quadrado e o banner horizontal compartilham o gerador da loja. A marca interna da interface permanece preservada.

As imagens devem preencher todo o retângulo, sem moldura ou cantos transparentes. O nome textual e o título da ficha são apenas “BuzinCast”. O Sobre usa a mesma marca e paleta, com acesso a site, fontes e créditos/licenças; o ® da Buzin Solutions recebe um espaço fino e aparece elevado. As faixas laterais que o Fire OS 6 adiciona a APKs instalados diretamente pertencem ao launcher; o banner da loja é horizontal e preenchido até as bordas.

Regeneração: execute `tools/GenerateBrandAssets.ps1` e depois `tools/GenerateLauncherAssets.ps1` com o Windows PowerShell (`powershell.exe -NoProfile -File ...`). A opção `-StoreDirectory` do primeiro gera apenas os arquivos da ficha. Henrique confirmou a concessão do registro de Buzin Solutions e autorizou o uso de ® no crédito da empresa; o símbolo não é atribuído ao nome Buzin Cast.

O CI verifica os arquivos e as referências do launcher com [tools/verify_brand.py](../tools/verify_brand.py) e [approved-brand.json](approved-brand.json). Após atualizar um APK em aparelho físico, confira também a imagem no launcher. O [histórico técnico](../BUZIN_CAST.md) registra o comportamento de cache observado no Fire OS 6.
