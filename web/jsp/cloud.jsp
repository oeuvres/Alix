<%@ page language="java"  pageEncoding="UTF-8" contentType="text/html; charset=UTF-8" trimDirectiveWhitespaces="true"%>
<%@ include file="prelude.jsp" %>
<%
final String q = tools.getString("q", null);
final String cat = tools.getString("cat", Cat.NOSTOP.name(), "catFreqs");

%>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Nuage de mots</title>
    <link rel="stylesheet" type="text/css" href="../static/obvil.css"/>
    <script src="../static/js/common.js">//</script>
  </head>
  <body class="cloud">
    <form id="filter">
       <select name="hpp" onchange="this.form.submit()">
        <option>500</option>
        <option>30</option>
        <option>50</option>
        <option>100</option>
        <option>200</option>
        <option>500</option>
        <option>1000</option>
       </select>

       <select name="cat" onchange="this.form.submit()">
          <option/>
          <%= catOptions(cat) %>
       </select>
       <input type="hidden" name="q" value="<%=Jsp.escape(q)%>"/>
    </form>
    <div id="wordcloud2"></div>
    <script src="../static/vendor/wordcloud2.js">//</script>
    <script src="../static/js/cloud.js">//</script>
</body>
</html>
