<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@include file="common.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8"/>
    <title>Alix</title>
    <link rel="stylesheet" type="text/css" href="../static/alix.css"/>
    <script src="../static/js/split.js">//</script>
  </head>
  <body class="split">
    <header id="header">
      <span class="corpus"><%=props.get("title")%></span>
      <form id="qform" name="qform" onsubmit="return dispatch(this)" target="page" action="snip.jsp">
        <input type="submit" name="send"
       style="position: absolute; left: -9999px; width: 1px; height: 1px;"
       tabindex="-1" />
        <span id="corpus"></span>
        <input id="q" name="q" autocomplete="off" size="40" autofocus="true" placeholder="Victor Hugo + Molière ; Dieu"/>
      </form>
      <div id="tabs">
        <a href="corpus.jsp" target="page" class="tab">Corpus</a>
        <a href="snip.jsp" target="page" class="tab">Résultats</a>
        <a href="doc.jsp" target="page" class="tab">Document</a>
        <a href="freqs.jsp" target="page" class="tab">Fréquences</a>
        <a href="cloud.jsp" target="page" class="tab">Nuage</a>
        <i target="page" class="tab">Concordancier</i>
      </div>
    </header>
    <div id="win">
      <div id="aside">
        <iframe id="panel" name="panel" src="facet.jsp">
        </iframe>
      </div>
      <div id="main">
        <div id="body">
          <iframe name="page" id="page" src="corpus.jsp">
          </iframe>
        </div>
        <footer id="footer">
          <iframe id="chrono" name="chrono" src="chrono.jsp">
          </iframe>
         </footer>
      </div>
    </div>
    <script src="../static/js/index.js">//</script>
  </body>
</html>