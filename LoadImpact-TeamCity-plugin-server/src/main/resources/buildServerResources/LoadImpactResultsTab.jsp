<%@ page import="com.loadimpact.teamcity_plugin.Debug" %>
<%@ page import="java.util.Enumeration" %>
<%@ taglib prefix="c" uri="http://java.sun.com/jsp/jstl/core" %>
<%@ taglib prefix="props" tagdir="/WEB-INF/tags/props" %>

<%
    Debug debug = new Debug("LoadImpactResultsTab.jsp");
    debug.print("resultUrl=%s", request.getAttribute("resultUrl"));
%>

<c:if test="${hasResults}">
    <c:if test="${not empty resultUrl}">
        <iframe width="1200" height="1200" src="${resultUrl}">
            <p>Your browser does not support iframes.</p>

            <p>Please, go directly to the <a href="${resultUrl}">results page at Load Impact</a>.</p>
        </iframe>
    </c:if>
</c:if>
