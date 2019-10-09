<%@ page language="java"  pageEncoding="UTF-8" contentType="text/html; charset=UTF-8"%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Nuage de mots</title>
    <link rel="stylesheet" type="text/css" href="../static/alix.css"/>
  </head>
  <body class="cloud">
    <form id="filter">
      <select name="sorter" onchange="this.form.submit()">
        <option/>
        <option value="nostop">Mots pleins</option>
        <option value="name">Noms propres</option>
        <option value="sub">Substantifs</option>
        <option value="verb">Verbes</option>
        <option value="adj">Adjectifs</option>
        <option value="adv">Adverbes</option>
        <option value="all">Tout</option>
      </select>
    </form>
    <div id="wordcloud2"></div>
    <script src="../static/vendors/wordcloud2.js">//</script>
    <script src="../static/js/cloud.js">//</script>
</body>
</html>