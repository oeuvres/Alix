<?xml version="1.0" encoding="UTF-8"?>
<!--
To index TEI files in lucene with Alix

LGPL  http://www.gnu.org/licenses/lgpl.html
© 2019 Frederic.Glorieux@fictif.org & Opteos


cdata-section-elements="cdata"
Attribute @cdata-section-elements is not allowed on element <xsl:transform>

-->
<xsl:transform version="1.0"
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns="http://www.w3.org/1999/xhtml"
  xmlns:tei="http://www.tei-c.org/ns/1.0"
  xmlns:alix="http://alix.casa"
  xmlns:saxon="http://saxon.sf.net/"
  
  
  exclude-result-prefixes="tei"
>
  <xsl:import href="flow.xsl"/>
  <xsl:import href="notes.xsl"/>
  <xsl:import href="toc.xsl"/>
  <xsl:output indent="yes" encoding="UTF-8" method="xml"/>
  <!-- chapter split policy -->
  <xsl:key name="split" match="
    tei:*[self::tei:div or self::tei:div1 or self::tei:div2][normalize-space(.) != ''][@type][
    contains(@type, 'article')
    or contains(@type, 'chapter')
    or contains(@subtype, 'split')
    or contains(@type, 'act')
    or contains(@type, 'poem')
    or contains(@type, 'letter')
    ]
    | tei:group/tei:text
    | tei:TEI/tei:text/tei:*/tei:*[self::tei:div or self::tei:div1 or self::tei:group or self::tei:titlePage  or self::tei:castList][normalize-space(.) != '']"
    use="generate-id(.)"/>
  <xsl:variable name="idHigh" select="/*/tei:teiHeader/tei:encodingDesc/tei:refsDecl//tei:att[text() = 'xm:id'][@cert='high']"/>
  <!-- Name of file, provided by caller -->
  <xsl:param name="filename"/>
  <!-- Get metas as a global var to insert fields in all chapters -->
  <xsl:variable name="info">
    <alix:field name="title" type="facet" value="{normalize-space($doctitle)}"/>
    <xsl:for-each select="/tei:TEI/tei:teiHeader/tei:fileDesc/tei:titleStmt">
      <xsl:for-each select="tei:author|tei:principal">
        <xsl:variable name="value">
          <xsl:apply-templates select="." mode="key"/>
        </xsl:variable>
        <xsl:if test="position() = 1">
          <alix:field name="author1" type="facet" value="{normalize-space($value)}"/>
        </xsl:if>
        <alix:field name="author" type="facets" value="{normalize-space($value)}"/>
      </xsl:for-each>
    </xsl:for-each>
    <xsl:if test="$byline != ''">
      <alix:field name="byline" type="store">
        <xsl:copy-of select="$byline"/>
      </alix:field>
    </xsl:if>
  </xsl:variable>
  <!-- an html bibliographic line -->
  <xsl:variable name="bibl-book">
    <xsl:if test="$byline != ''">
      <span class="byline">
        <xsl:copy-of select="$byline"/>
      </span>
    </xsl:if>
    <xsl:if test="$doctitle != ''">
      <xsl:text> </xsl:text>
      <em class="title">
        <xsl:copy-of select="$doctitle"/>
      </em>
    </xsl:if>
    <xsl:variable name="year" select="substring($docdate, 1, 4)"/>
    <xsl:if test="string(number($year)) != 'NaN'">
      <xsl:text> </xsl:text>
      <span class="year">
        <xsl:text>(</xsl:text>
        <xsl:value-of select="$year"/>
        <xsl:text>)</xsl:text>
      </span>
    </xsl:if>
  </xsl:variable>

  <xsl:template match="/*">
    <!-- XML book is handled as nested lucene documents (chapters) -->
    <alix:book>
      <xsl:attribute name="xml:id">
        <xsl:choose>
          <xsl:when test="/*/@xml:id and /*/@xml:id != ''">
            <xsl:value-of select="@xml:id"/>
          </xsl:when>
          <xsl:when test="$filename != ''">
            <xsl:value-of select="$filename"/>
          </xsl:when>
          <xsl:otherwise>
            <xsl:message terminate="no">NO id for this book, will be hard to retrieve</xsl:message>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:attribute>
      <xsl:copy-of select="$info"/>
      <!-- Date of global book -->
      <xsl:variable name="year" select="substring($docdate, 1, 4)"/>
      <xsl:if test="string(number($year)) != 'NaN'">
        <alix:field name="year" type="int">
          <xsl:attribute name="value">
            <xsl:value-of select="$year"/>
          </xsl:attribute>
        </alix:field>
      </xsl:if>

      <alix:field name="bibl" type="meta">
        <xsl:copy-of select="$bibl-book"/>
      </alix:field>
      <alix:field name="toc" type="store">
        <xsl:call-template name="toc"/>
      </alix:field>
      <!-- process chapters -->
      <xsl:apply-templates mode="alix" select="*"/>
    </alix:book>
  </xsl:template>

  <!-- Default mode alix -->
  <xsl:template match="tei:teiHeader" mode="alix"/>
  <xsl:template match="*" mode="alix">
    <xsl:apply-templates select="*" mode="alix"/>
  </xsl:template>


  <xsl:template mode="alix" match="
    tei:group/tei:text | tei:group | tei:body |
    tei:div | tei:div0 | tei:div1 | tei:div2 | tei:div3 | tei:div4 | tei:div5 | tei:div6 | tei:div7
    "
    >
    <xsl:choose>
      <!-- declared section -->
      <xsl:when test="
           contains(@type, 'article')
        or contains(@type, 'chapter')
        or contains(@subtype, 'split')
        or contains(@type, 'act')
        or contains(@type, 'poem')
        or contains(@type, 'letter')
        ">
        <xsl:call-template name="chapter"/>
      </xsl:when>
      <!-- section container -->
      <xsl:when test="
      descendant::*[
        contains(@type, 'article')
        or contains(@type, 'chapter')
        or contains(@subtype, 'split')
        or contains(@type, 'act')
        or contains(@type, 'poem')
        or contains(@type, 'letter')
       ]">
        <xsl:apply-templates select="*" mode="alix"/>
      </xsl:when>
      <!-- blocks of text, open a chapter -->
      <xsl:when test="tei:p|tei:l|tei:list|tei:argument|tei:table">
        <xsl:call-template name="chapter"/>
      </xsl:when>
      <xsl:when test="self::tei:body">
        <xsl:apply-templates select="*" mode="alix"/>
      </xsl:when>
      <xsl:when test="./*//tei:head[contains(., 'Chapitre') or contains(., 'chapitre')]">
        <xsl:apply-templates select="*" mode="alix"/>
      </xsl:when>
      <!-- maybe not best grain, but not too small -->
      <xsl:otherwise>
        <xsl:call-template name="chapter"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>
  
  <xsl:template name="next">
    <xsl:choose>
      <xsl:when test="following-sibling::*[1]">
        <xsl:apply-templates select="following-sibling::*[1]" mode="analytic"/>
      </xsl:when>
      <xsl:when test="following::*[1]">
        <xsl:variable name="next" select="following::*[1]/descendant::*[contains(' article chapter act poem letter ', @type) or @subtype = 'split'][1]"/>
        <xsl:choose>
          <xsl:when test="$next">
            <xsl:apply-templates select="$next" mode="analytic"/>
          </xsl:when>
          <xsl:otherwise>
            <xsl:apply-templates select="following::*[1]" mode="analytic"/>
          </xsl:otherwise>
        </xsl:choose>
      </xsl:when>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="prev">
    <xsl:choose>
      <xsl:when test="preceding-sibling::*[1]">
        <xsl:apply-templates select="preceding-sibling::*[1]" mode="analytic"/>
      </xsl:when>
      <xsl:when test="preceding::*[1][not(ancestor-or-self::tei:teiHeader)]">
        <xsl:apply-templates select="preceding::*[1]" mode="analytic"/>
      </xsl:when>
    </xsl:choose>
  </xsl:template>
  
  <xsl:template match="*" mode="analytic">
    <xsl:for-each select="ancestor-or-self::*[not(self::tei:TEI)][not(self::tei:text)][not(self::tei:body)]">
      <xsl:if test="position() != 1"> — </xsl:if>
      <xsl:apply-templates select="." mode="title"/>
    </xsl:for-each>
  </xsl:template>
  
  <xsl:template name="chapter">
    <alix:chapter>
      <!-- id is here supposed to be unique ; maybe dangerous… -->
      <xsl:if test="@xml:id and $idHigh">
        <xsl:attribute name="xml:id">
          <xsl:value-of select="@xml:id"/>
        </xsl:attribute>
      </xsl:if>
      <xsl:copy-of select="$info"/>
      <!-- local date or replicate book date ? -->
      <xsl:variable name="chapyear" select="substring(@when, 1, 4)"/>
      <xsl:variable name="bookyear" select="substring($docdate, 1, 4)"/>
      <xsl:choose>
        <xsl:when test="string(number($chapyear)) != 'NaN'">
          <alix:field name="year" type="int" value="{$chapyear}"/>
        </xsl:when>
        <xsl:when test="string(number($bookyear)) != 'NaN'">
          <alix:field name="year" type="int" value="{$bookyear}"/>
        </xsl:when>
      </xsl:choose>
      <alix:field name="bibl" type="meta">
        <xsl:call-template name="bibl"/>
      </alix:field>
      <alix:field name="analytic" type="meta">
        <xsl:call-template name="analytic"/>
      </alix:field>
      <xsl:variable name="pages">
        <xsl:call-template name="pages"/>
      </xsl:variable>     
      <xsl:if test="$pages != ''">
        <alix:field name="pages" type="meta">
          <xsl:copy-of select="$pages"/>
        </alix:field>
      </xsl:if>
      <xsl:variable name="prev">
        <xsl:call-template name="prev"/>
      </xsl:variable>     
      <xsl:if test="$prev != ''">
        <alix:field name="prev" type="store">
          <xsl:value-of select="$prev"/>
        </alix:field>
      </xsl:if>
      <xsl:variable name="next">
        <xsl:call-template name="next"/>
      </xsl:variable>     
      <xsl:if test="$next != ''">
        <alix:field name="next" type="store">
          <xsl:value-of select="$next"/>
        </alix:field>
      </xsl:if>
      <alix:field name="text" type="text">
        <xsl:apply-templates/>
        <xsl:processing-instruction name="index_off"/>
        <xsl:call-template name="footnotes"/>
        <xsl:processing-instruction name="index_on"/>
      </alix:field>
    </alix:chapter>
  </xsl:template>
  <!-- Terms in subtitle could be boosted ? -->
  <xsl:template name="children">
    <xsl:for-each select=".//tei:div">
      <alix:field name="child" type="text">
        <xsl:apply-templates select="." mode="title"/>
      </alix:field>
    </xsl:for-each>
  </xsl:template>
  <xsl:template name="parents">
    <xsl:for-each select="ancestor-or-self::*">
      <!--
      <xsl:sort order="descending" select="position()"/>
      -->
      <xsl:choose>
        <xsl:when test="self::tei:TEI"/>
        <xsl:when test="self::tei:text"/>
        <xsl:when test="self::tei:body"/>
        <xsl:otherwise>
          <alix:field name="parent" type="text">
            <xsl:apply-templates select="." mode="title"/>
          </alix:field>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:for-each>
  </xsl:template>
</xsl:transform>
