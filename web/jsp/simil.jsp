<%@ page language="java" contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ include file="prelude.jsp" %>
<!DOCTYPE html>
<html>
  <head>
    <meta charset="UTF-8">
    <title>Conparaison [Obvil]</title>
    <style>
body, html {
  height: 100%;
  margin: 0;
  padding: 0;
}
iframe {
  border: none;
  margin: 0;
  padding: 0;
  width: 50%;
}
#left {
}

#right {
  float: right;
}
    </style>
  </head>
  <body style="padding: 0; margin">
    <iframe id="left" name="left" src="reflist.jsp" width="50%" height="99.5%">
    </iframe>
    <iframe id="right" name="right" src="list.jsp" width="50%" height="99.5%">
    </iframe>
    <script type="text/javascript">
    </script>
  </body>
</html>
