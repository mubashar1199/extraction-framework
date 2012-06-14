package org.dbpedia.extraction.mappings

import org.dbpedia.extraction.wikiparser._
import org.dbpedia.extraction.util.Language
import java.util.Locale
import org.dbpedia.extraction.destinations.{Graph, Quad, Dataset}
import org.openrdf.model.{Literal, URI, Resource, Value}
import org.openrdf.model.impl.ValueFactoryImpl
import util.control.Breaks._
import java.io.FileNotFoundException
import java.io.FileWriter
import java.net.URLEncoder
import java.net.URL
import java.lang.StringBuffer
import xml.{XML, Node => XMLNode, NodeSeq}
import scala.util.matching.Regex
import collection.mutable.{HashMap, Stack, ListBuffer, HashSet}
import org.dbpedia.extraction.mappings.wikitemplate._
import org.dbpedia.extraction.mappings.wikitemplate.wiktionary.bindinghandler._
import org.dbpedia.extraction.mappings.wikitemplate.wiktionary.postprocessor._

//some of my utilities
import org.dbpedia.extraction.mappings.wikitemplate.MyNodeList._
import org.dbpedia.extraction.mappings.wikitemplate.MyNode._
import org.dbpedia.extraction.mappings.wikitemplate.MyStack._
import org.dbpedia.extraction.mappings.wikitemplate.TimeMeasurement._
import org.dbpedia.extraction.mappings.wikitemplate.VarBinder._
import org.dbpedia.extraction.mappings.wikitemplate.Logging._
import org.dbpedia.extraction.mappings.wikitemplate.MyLinkNode._
import WiktionaryPageExtractor._ //companion


/**
 * parses (wiktionary) wiki pages
 * is meant to be configurable for multiple languages
 *
 * is even meant to be usable for non-wiktionary wikis -> arbitrary wikis, but where all pages follow a common schema
 * but in contrast to infobox-focused extraction, we *aim* to be more flexible:
 * dbpedia core is hardcoded extraction. here we try to use a meta-language describing the information to be extracted
 * this is done via xml containing wikisyntax snippets (called templates) containing placeholders (called variables), which are then bound
 *
 * we also extended this approach to match the wiktionary schema
 * a page can contain information about multiple entities (sequential blocks)
 *
 * @author Jonas Brekle <jonas.brekle@gmail.com>
 * @author Sebastian Hellmann <hellmann@informatik.uni-leipzig.de>
 */

class WiktionaryPageExtractor( context : {} ) extends Extractor {
  override def extract(page: PageNode, subjectUri: String, pageContext: PageContext): Graph =
  {
    val cache = new Cache

    Logging.printMsg("start "+subjectUri+" threadID="+Thread.currentThread().getId(),2)
    // wait a random number of seconds. kills parallelism - otherwise debug output from different threads is mixed
    if(logLevel > 0){
      val r = new scala.util.Random
      Thread sleep r.nextInt(10)*1000
    }

    val quads = new ListBuffer[Quad]()
    val entityId = page.title.decoded
    cache.savedVars("entityId") = entityId

    //skip some useless pages
    for(start <- ignoreStart){
        if(entityId.startsWith(start)){
            Logging.printMsg("ignored "+entityId,1)
            return new Graph(quads.toList)
        }
    }
    for(end <- ignoreEnd){
        if(entityId.endsWith(end)){
            Logging.printMsg("ignored "+entityId,1)
            return new Graph(quads.toList)
        }
    }

    Logging.printMsg("processing "+entityId, 1)

    measure {
      //this is also the base-url (all nested blocks will get uris with this as a prefix)
      cache.blockURIs("page") = vf.createURI(resourceNS + urify(entityId))
      cache.savedVars("block") = cache.blockURIs("page").stringValue

      //generate template-unrelated triples that are configured for the page
      try {
        quads appendAll makeTriples(pageConfig.rt, new HashMap[String, List[Node]], pageConfig.name, cache) 
      } catch {
        case e : WiktionaryException => Logging.printMsg("#error generating page triples: "+e.toString, 2)
      }

      val pageStack =  new Stack[Node]().pushAll(page.children.reverse).filterSpaces

      val proAndEpilogBindings : ListBuffer[Tuple2[Tpl, VarBindingsHierarchical]] = new ListBuffer
      //handle prolog (beginning) (e.g. "see also") - not related to blocks, but to the main entity of the page
      for(prolog <- languageConfig \ "page" \ "prologs" \ "template"){
        val prologtpl = Tpl.fromNode(prolog)
        Logging.printMsg("try "+prologtpl.name, 2)
         try {
          proAndEpilogBindings.append( (prologtpl, VarBinder.parseNodesWithTemplate(prologtpl.wiki.clone, pageStack)) )
        } catch {
          case e : WiktionaryException => proAndEpilogBindings.append( (prologtpl, e.vars) )
        }
      }

      //handle epilog (ending) (e.g. "links to other languages") by parsing the page backwards
      val rev = new Stack[Node] pushAll pageStack //reversed
      if(rev.headOption.isDefined && (!rev.head.isInstanceOf[TextNode] || (rev.head.isInstanceOf[TextNode] && !rev.head.asInstanceOf[TextNode].text.equals("\n")))){
        rev.push(new TextNode("\n",0))
      }
      for(epilog <- languageConfig \ "page" \ "epilogs" \ "template"){
        val epilogtpl = Tpl.fromNode(epilog)
        try {
          proAndEpilogBindings.append( (epilogtpl, VarBinder.parseNodesWithTemplate(epilogtpl.wiki.reverseSwapList, rev)) )
        } catch {
          case e : WiktionaryException => proAndEpilogBindings.append( (epilogtpl, e.vars) )
        }
      }
      Logging.printMsg(proAndEpilogBindings.size+ " prologs and epilogs found", 2)

      //apply consumed nodes (from the reversed page) to pageStack  (unreversed)
      pageStack.clear
      pageStack pushAll rev

      //handle the bindings from pro- and epilog
      proAndEpilogBindings.foreach({case (tpl : Tpl, tplBindings : VarBindingsHierarchical) => {
        try {
         quads appendAll handleFlatBindings(tplBindings.getFlat(), pageConfig, tpl, cache, cache.blockURIs("page").stringValue)
        } catch { case _ => } //returned bindings are wrong
      }})
      Logging.printMsg("pro- and epilog bindings handled", 2)

      //keep track where we are in the page block hierarchy
      val curOpenBlocks = new ListBuffer[Block]()
      curOpenBlocks append pageConfig
      var curBlock : Block = pageConfig
      var counter = 0
      //keep track if we consumed at least one node in this while run - if not, drop one node at the end
      var consumed = false
      while(pageStack.size > 0){
        counter += 1
        if(counter % 100 == 0){
            Logging.printMsg(""+counter+"/"+page.children.size+" nodes inspected "+entityId,1)
        }
        Logging.printMsg("page node: "+pageStack.head.toWikiText(), 2)
        consumed = false

        val possibleBlocks = curOpenBlocks.map(_.blocks).foldLeft(List[Block]()){ _ ::: _ } //:: pageConfig
        //val possibleTemplates = curOpenBlocks.foldLeft(List[Tpl]()){(all,cur)=>all ::: cur.templates} 
        //println("possibleTemplates="+ possibleTemplates.map(t=>t.name) )
        //println("possibleBlocks="+ possibleBlocks.map(_.name) )

        //try matching this blocks templates
        var triedTemplatesCounter = 0
        while(triedTemplatesCounter < curBlock.templates.size && pageStack.size > 0){
        for(tpl <- curBlock.templates){
          triedTemplatesCounter += 1
          //println(""+triedTemplatesCounter+"/"+curBlock.templates.size)
          //for(tpl <- possibleTemplates){
          Logging.printMsg("trying template "+tpl.name, 3)
          val pageCopy = pageStack.clone 
          //println(pageStack.take(1).map(_.dumpStrShort).mkString)
          try {
            //println("vs")
            //println(block.indTpl.tpl.map(_.dumpStrShort).mkString )

            val blockBindings = VarBinder.parseNodesWithTemplate(tpl.wiki.clone, pageStack)

            //generate triples
            //println(tpl.name +": "+ blockBindings.dump())
            quads appendAll handleFlatBindings(blockBindings.getFlat(), curBlock, tpl, cache, cache.blockURIs(curBlock.name).stringValue)

            //no exception -> success -> stuff below here will be executed on success
            consumed = true
            //reset counter, so all templates need to be tried again
            triedTemplatesCounter = 0
            Logging.printMsg("finished template "+tpl.name+" successfully", 3)
          } catch {
            case e : WiktionaryException => restore(pageStack, pageCopy) //did not match
          }
        }
        }

        if(pageStack.size > 0){
          // try recognizing block starts of blocks. if recognized we go somewhere UP the hierarchy (the block ended) or one step DOWN (new sub block)
          // each block has a "indicator-template" (indTpl)
          // when it matches, the block starts. and from that template we get bindings that describe the block

          Logging.printMsg("trying block indicator templates. page node: "+pageStack.head.toWikiText, 3)
          breakable {
            for(block <- possibleBlocks){
              if(block.indTpl == null){
                //continue - the "page" block has no indicator template, it starts implicitly with the page
              } else {
                //these two are need in case of exeption to undo stuff
                val pageCopy = pageStack.clone
                val blockURICopy = cache.savedVars("block")
                val consumedCopy = consumed
                try {
                  //println("vs")
                  //println(block.indTpl.tpl.map(_.dumpStrShort).mkString )
                  val blockIndBindings = VarBinder.parseNodesWithTemplate(block.indTpl.wiki.clone, pageStack)

                  val parentBlockUri = if(!curOpenBlocks.contains(block)){
                    cache.blockURIs(curBlock.name).stringValue
                  } else {
                    cache.blockURIs(block.parent.name).stringValue
                  }
                  cache.savedVars("block") = parentBlockUri

                  quads appendAll handleFlatBindings(blockIndBindings.getFlat(), block, block.indTpl, cache, parentBlockUri)

                  //no exception -> success -> stuff below here will be executed on success
                  consumed = true

                  curBlock = block //switch to new block
                  Logging.printMsg("block indicator template "+block.indTpl.name+" matched", 2)
                  
                  //check where in the hierarchy the new opended block is
                  if(!curOpenBlocks.contains(block)){
                    // the new block is not up in the hierarchy
                    // go one step down/deeper 
                    //println("new block detected: "+block.name+" in curOpenBlocks= "+curOpenBlocks.map(_.name))
                    curOpenBlocks append block
                  } else {
                    //the new block somewhere up the hierarchy
                    //go to the parent of that
                    //println("parent block detected: "+block.name+" in curOpenBlocks= "+curOpenBlocks.map(_.name))
                    val newOpen = curOpenBlocks.takeWhile(_ != block)
                    curOpenBlocks.clear()
                    curOpenBlocks.appendAll(newOpen) 
                    //take a new turn
                    curOpenBlocks.append(block)// up
                  }
                  
                  //generate template-unrelated triples that are configured for the block
                  try {
                    quads appendAll makeTriples(block.rt, new HashMap[String, List[Node]], block.name, cache) 
                  } catch {
                    case e : WiktionaryException => Logging.printMsg("#error generating block triples: "+e.toString, 2)
                  }
                  break; //dont match another block indicator template right away (continue with this blocks templates)
                } catch {
                  case e : WiktionaryException => {
                    //did not match
                    Logging.printMsg("exception while trying to match template "+e.toString, 2)
                    restore(pageStack, pageCopy) //restore the page -> dont consume anything
                    cache.savedVars("block") = blockURICopy //restore the current block URI
                    consumed = consumedCopy
                  }
                }
              }
            }
          }
        }
        if(!consumed){
          Logging.printMsg("skipping unconsumable node ", 2)
          val unconsumeableNode = pageStack.pop
          unconsumeableNode match {
            case tn : TextNode => if(tn.text.startsWith(" ") || tn.text.startsWith("\n")){
                pageStack.push(tn.copy(text=tn.text.substring(1)))
            }
            case _ => 
          }
        }
      }

    } report {
      duration : Long => Logging.printMsg("took "+ duration +"ms", 1)
    }
    
    //remove duplicate triples
    val quadsDistinct = quads.groupBy(_.renderNTriple).map(_._2.head).toList

    //postprocessing (schema transformation)
    val quadsPostProcessed = if(postprocessor.isDefined && quadsDistinct.size > 0){
        postprocessor.get.process(quadsDistinct, cache.blockURIs("page").stringValue).groupBy(_.renderNTriple).map(_._2.head).toList
    } else quadsDistinct
    
    //sorting (by length, if equal length: alphabetical)
    val quadsSorted = quadsPostProcessed.sortWith((q1, q2)=>{
      val subjComp = myCompare(q1.subject.toString, q2.subject.toString)
      if(subjComp == 0){
        myCompare(q1.predicate.toString, q2.predicate.toString) < 0
      } else subjComp < 0
    })

    Logging.printMsg(""+quadsSorted.size+" quads extracted for "+entityId, 1)
    quadsSorted.foreach( q => { Logging.printMsg(q.renderNTriple, 1) } )
    Logging.printMsg("finish "+subjectUri+" threadID="+Thread.currentThread().getId(), 2)
    new Graph(quadsSorted)
  }

  /**
   * silly helper function
   */
  protected def restore(st : Stack[Node], backup : Stack[Node]) : Unit = {
    st.clear
    st pushAll backup.reverse
  }

  def handleFlatBindings(bindings : VarBindings, block : Block, tpl : Tpl, cache : Cache, thisBlockURI : String) : List[Quad] = {
    val quads = new ListBuffer[Quad]

    if(tpl.pp.isDefined){
        val handler = Class.forName(tpl.pp.get.clazz).newInstance.asInstanceOf[BindingHandler]
        return handler.process(bindings, thisBlockURI, cache, tpl.pp.get.parameters)
    }
    var bindingsMatchedAnyResultTemplate = false
    bindings.foreach( (binding : HashMap[String, List[Node]]) => {
      Logging.printMsg("bindings "+binding, 2)
      tpl.resultTemplates.foreach( (rt : ResultTemplate) => {
        try {
          quads appendAll makeTriples(rt, binding, block.name, cache) 
          bindingsMatchedAnyResultTemplate = true
        } catch {
          case e1:java.util.NoSuchElementException => Logging.printMsg("missing binding: "+e1, 3)
          case e => Logging.printMsg("exception while processing bindings: "+e, 3)
        }
      })
    })
    if(!bindingsMatchedAnyResultTemplate){
        throw new WiktionaryException("result templates were not satisfiable", new VarBindingsHierarchical, None)    
    }
    quads.toList
  }

  def makeTriples(rt : ResultTemplate, binding : HashMap[String, List[Node]], blockName : String, cache : Cache) : List[Quad] = {
          val quads = new ListBuffer[Quad]
          rt.triples.foreach( (tt : TripleTemplate) => {
            try{
            //apply the same placeholder-processing to s, p and o => DRY:
            val in = Map("s"->tt.s, "p"->tt.p, "o"->tt.o)
            val out = in.map( kv => {
                val replacedVars = varPattern.replaceAllIn( kv._2, (m) => {
                        val varName = m.matched.substring(1);//exclude the $ symbol
                        val replacement = if(binding.contains(varName)){
                            binding(varName).toReadableString
                        } else if(cache.savedVars.contains(varName)){
                            cache.savedVars(varName)
                        } else if(!tt.optional){ 
                            throw new Exception("missing binding for "+varName)
                        } else {
                            //println("continue")
                            throw new ContinueException("skip optional triple")
                        }
                        //to prevent, that recorded ) symbols mess up the regex of map and uri
                        val varValue = replacement.replace(")", escapeSeq2).replace("(", escapeSeq1)
                        if(saveVars.contains(varName)){
                            cache.savedVars(varName.toUpperCase) = varValue
                        }
                        varValue
                })
                var functionsResolved = replacedVars
                var matched = false
                var i = 0
                do{ //replace function calls inside out (the pattern each time only matches the innermost fun)
                    i += 1
                    matched = false                    
                    functionsResolved = funPattern.replaceAllIn(functionsResolved, (m) => {
                        matched = true
                        val funName = m.group(1)
                        val funArg = m.group(2)
                        val res = funName match {
                            case "uri" => urify(funArg)
                            case "map" => map(funArg)
                            case "assertMapped" => if(!(mappings.contains(funArg))){throw new Exception("assertion failed: existing key in mapings for "+funArg)} else {funArg}
                            case "getId" => cache.matcher.getId(funArg)
                            case "makeId" => cache.matcher.makeId(funArg)
                            case "getOrMakeId" => cache.matcher.getOrMakeId(funArg)
                            case _ =>  {matched = false; funArg} // if unknown keep arg
                        }
                        res
                    }) 
                } while (matched && i < 10)
                (kv._1, functionsResolved.replace(escapeSeq2, ")").replace(escapeSeq1, "("))
            })
            
            val s = out("s")
            val p = out("p")
            val o = out("o")
            
            //to trigger exceptions if URL doesnt start with http:// etc, i dont use a real regex because its to heavy for every URI            
            new URL(s)
            if(s.contains(" ")){
                throw new Exception("invalid subject URI <"+s+">")
            }
            new URL(p)
            if(p.contains(" ")){
                throw new Exception("invalid predicate URI <"+p+">")
            }
            Logging.printMsg("emmiting triple "+s+" "+p+" "+o, 2)
            //determine if o is literal or URI
            val oObj = if(tt.oType == "URI"){
                new URL(o)
                if(o.contains(" ")){
                    throw new Exception("invalid object URI <"+o+">")
                }
                val oURI = vf.createURI(o)
                if(tt.oNewBlock){
                  cache.blockURIs(blockName) = oURI //save for later
                  //println(blockURIs)
                  Logging.printMsg("entering "+oURI, 2)
                  cache.savedVars("block") = o
                }
                oURI
              } else {
                vf.createLiteral(o.trim)
              }
            quads += new Quad(langObj, datasetURI, vf.createURI(s), vf.createURI(p), oObj, tripleContext)
            } catch {
              case ce : ContinueException => //skip
              case e : Exception => throw e //propagate
            }
          })
    quads.toList
  }
}

object WiktionaryPageExtractor {
  //load config from xml
  private val config = XML.loadFile("config.xml")

  val properties : Map[String, String] = (config \ "properties" \ "property").map(
      (n : XMLNode) =>
        ( (n \ "@name").text,
          (n \ "@value").text
        )
      ).toMap

  val language = properties("language")
  val logLevel = properties("logLevel").toInt

  val langObj = new Language(language, new Locale(language))

  val vf = ValueFactoryImpl.getInstance
  Logging.level = logLevel
  Logging.printMsg("wiktionary loglevel = "+logLevel,0)

  val ns = properties.get("ns").getOrElse("http://undefined.com/")
  val resourceNS = ns +"resource/"
  val termsNS = ns +"terms/"

  val varPattern = new Regex("\\$[a-zA-Z0-9]+")  
  //val mapPattern = new Regex("map\\([^)]*\\)")
  //val uriPattern = new Regex("uri\\([^)]*\\)")
  val funPattern = new Regex("([a-zA-Z0-9]+)\\(([^)(]*)\\)")
  val escapeSeq1 = "%#*+~" //an unlikely sequence of characters (duh)
  val escapeSeq2 = "%#*+~" //an unlikely sequence of characters (duh)
  //val splitPattern = new Regex("\\W")
  //val xmlPattern = new Regex("</?[^>]+/?>")

  private val languageConfig = XML.loadFile("config-"+language+".xml")

  private val mappings : Map[String, String] = (languageConfig \\ "mapping").map(
      (n : XMLNode) => //language specific mappings (from language ("Spanisch") to global vocabulary ("spanish"))
        ( (n \ "@from").text,
          (n \ "@to").text
        )
      ).toMap ++ (config \ "mappings"\ "mapping").map(
      (n : XMLNode) => // general mappings (from language codes ("es") to global vocabulary ("spanish"))
        ( (n \ "@from").text,
          (n \ "@to").text
        )
      ).toMap

  /**
  * a list of var names that should be saved
  */
  val saveVars = (languageConfig \ "saveVars" \ "var").map((n : XMLNode) => (n \ "@name").text)

  /**
  * a list of strings, that - if the page title starts with it - makes the extractor skip it
  */
  val ignoreStart = (languageConfig \ "ignore" \ "page").filter(_.attribute("startsWith").isDefined).map((n : XMLNode) => (n \ "@startsWith").text)
  
  /**
  * a list of strings, that - if the page title ends with it - makes the extractor skip it
  */
  val ignoreEnd = (languageConfig \ "ignore" \ "page").filter(_.attribute("endsWith").isDefined).map((n : XMLNode) => (n \ "@endsWith").text)

  /**
  * a optional PostProcessor instance, it can transform the generated Quads 
  */
  val postprocessor : Option[PostProcessor] = if((languageConfig \ "postprocessing" \ "@enabled").text.equals("true")){Some(Class.forName((languageConfig \ "postprocessing" \ "@ppClass").text).getConstructor(classOf[NodeSeq]).newInstance((languageConfig \ "postprocessing" \ "config")).asInstanceOf[PostProcessor])} else None
  
  /**
  * the root of the config
  */
  val pageConfig = new Page((languageConfig \ "page")(0))

  /**
  * ? Quad needs this :)
  */
  val datasetURI : Dataset = new Dataset("wiktionary.dbpedia.org")
  
  /**
  * the graph of all produced quads
  */
  val tripleContext = vf.createURI(ns.replace("http://", "http://"+language+"."))

  /**
  * urify
  * do some pseudo-urlencoding 
  */
  def urify(in:String):String = in.replace("	"," ").replace(" ", "_").replace("'", "").replace("(", "").replace(")", "").replace("[ ", "").replace("]", "").replace("{", "").replace("}", "").replace("*", "").replace("+", "").replace("#", "").replace("/", "").replace("\\", "").replace("<", "").replace(">", "")//URLEncoder.encode(in.trim, "UTF-8")

  val cleanPattern = new Regex("[^a-zA-Z]")
  def getCleaned(dirty:String) = cleanPattern.replaceAllIn(dirty, "").trim
  def myCompare(s1 : String, s2 : String) : Int = {
      val s1l = s1.length
      val s2l = s2.length 
      if(s1l == s2l){
        s1.compareTo(s2) 
      } else {
        s1l - s2l
      }
  }

  def map(in:String):String = {
    val clean = getCleaned(in)
    mappings.getOrElse(clean, clean)
  }
}

class Matcher {
  val ids = new HashMap[String, ListBuffer[String]]()
  val cache = new HashMap[String, String]()

  def matchId(in:String) : Tuple2[String, Float] = {
    var max = 0.0f
    var maxId = ""
    for(id <- ids.keySet){
      for(source <- ids(id)){
        val cur = sim(in, source)
        if(cur > max){
          max = cur
          maxId = id
        }
      }
    }
    (maxId, max)
  }

  def getId(in : String) : String = {
    if(cache.contains(in)){return cache(in)}
    val matched = matchId(in)
    if(matched._1.equals("")){
      makeId(in)
    } else {
      ids(matched._1).append(in)
      matched._1
    }
  }

  def getOrMakeId(in : String) : String = {
    val matched = matchId(in)
    if(matched._1.equals("") || matched._2 < 0.5){
      makeId(in)
    } else {
      ids(matched._1).append(in)
      matched._1
    }
  }

  def makeId(in : String) : String = {
    val id = (ids.size + 1).toString
    val values = new ListBuffer[String]()
    values.append(in)
    ids(id) = values
    cache(in) = id
    id
  }

  def sim(s1 : String, s2 : String) : Float = {
    if(s1.equals(s2)){
      1.0f
    } else if (s1.containsSlice(s2) || s2.containsSlice(s1)){
      0.9f
    } else {
      //overlap(ngrams(s1,3), ngrams(s2,3))
      levenshtein(s1, s2)
    }
  }
  def ngrams(s:String, n:Int) : Set[String] = {
    (("#"*(n-1))+s+("#"*(n-1))).sliding(n).toSet
  }
  def jaccard(s1ngrams : Set[String], s2ngrams: Set[String]) : Float = {
    val intersectSize = s1ngrams.intersect(s2ngrams).size
    val unionSize = s1ngrams.union(s2ngrams).size
    if(unionSize == 0){
      1.0f
    } else {
      intersectSize.toFloat / unionSize
    }
  }
  def dice(s1ngrams : Set[String], s2ngrams: Set[String]) : Float = {
    val intersectSize = s1ngrams.intersect(s2ngrams).size
    val summedSize = s1ngrams.size + s2ngrams.size
    if(summedSize == 0){
      1.0f
    } else {
      (2 * intersectSize).toFloat / summedSize
    }
  }
  def overlap(s1ngrams : Set[String], s2ngrams: Set[String]) : Float = {
    val intersectSize = s1ngrams.intersect(s2ngrams).size
    val s1Size = s1ngrams.size
    val s2Size = s2ngrams.size
    val minSize = if(s1Size < s2Size) s1Size else s2Size
    if(minSize == 0){
      0.0f
    } else {
      intersectSize.toFloat / minSize
    }
  }
  def levenshtein_dist(s1: String, s2: String) : Int = {
      val memo = scala.collection.mutable.Map[(List[Char],List[Char]),Int]()
      def min(a:Int, b:Int, c:Int) = scala.math.min( scala.math.min( a, b ), c)
      def sd(s1: List[Char], s2: List[Char]): Int = {
        if (memo.contains((s1,s2)) == false)
          memo((s1,s2)) = (s1, s2) match {
            case (_, Nil) => s1.length
            case (Nil, _) => s2.length
            case (c1::t1, c2::t2)  => min( sd(t1,s2) + 1, sd(s1,t2) + 1,
                                           sd(t1,t2) + (if (c1==c2) 0 else 1) )
          }
        memo((s1,s2))
      }

      sd( s1.toList, s2.toList )
  }
  def levenshtein(s1: String, s2: String) : Float = {
    val s1Size = s1.size
    val s2Size = s2.size
    1 - (levenshtein_dist(s1, s2).toFloat / (scala.math.max(s1Size, s2Size) ))
  }

  def clear = ids.clear
}

class Cache {
    //to cache last used blockURIs (from block name to its uri)
    val blockURIs = new HashMap[String, URI]
    //to save variable bindings
    val savedVars = new HashMap[String, String]()
    //which variables to save
    val matcher = new Matcher
}


