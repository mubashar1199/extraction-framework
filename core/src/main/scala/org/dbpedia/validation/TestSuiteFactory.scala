package org.dbpedia.validation

import org.apache.jena.query.{QueryExecutionFactory, QueryFactory}
import org.apache.jena.rdf.model.{Model, ModelFactory}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object TestSuiteFactory {

  val validatorReferencesToIndexMap: mutable.Map[ValidatorReference,Int] = mutable.Map()

  // TODO use InputStream
  def loadTestSuite(pathToTestCaseFile: String): TestSuite = {

    // TODO: Jena could not read https and ttl is not well formed
    //       val testCaseTTL = new URL("https://raw.githubusercontent.com/dbpedia/extraction-framework/master/new_release_based_ci_tests_draft.ttl")
    //       IOUtils.copy(testCaseTTL.openStream(), System.out)

    val testsRdfModel = ModelFactory.createDefaultModel()
    testsRdfModel.read(pathToTestCaseFile)

    // TODO: replace validatorReferencesToIndexMap By make TestSuite containing array of testCases
    //       ( testcase has idx. of trggr & val array )
    TestSuite(
      loadIriTriggers(testsRdfModel),
      loadIriValidators(testsRdfModel),
      validatorReferencesToIndexMap.toMap /*ensure immutability*/)
  }

  private def loadIriTriggers(m_tests: Model): Array[IriTrigger] = {

    val triggersQuery = QueryFactory.create(iriTriggerQueryStr())

    val triggersResultSet = QueryExecutionFactory.create(triggersQuery, m_tests).execSelect()

    val iriTriggers = ArrayBuffer[IriTrigger]()

    while (triggersResultSet.hasNext) {

      val triggerSolution = triggersResultSet.next()
      val triggerIri = triggerSolution.getResource("trigger").getURI

      val triggeredValidatorsQuery = QueryFactory.create(triggeredValidatorsQueryStr(triggerIri))
      val triggeredValidatorsResultSet = QueryExecutionFactory.create(triggeredValidatorsQuery,m_tests).execSelect()

      val validatorReferences = ArrayBuffer[ValidatorReference]()
      while (triggeredValidatorsResultSet.hasNext)
        validatorReferences.append(triggeredValidatorsResultSet.next().getResource("validator").getURI)

      iriTriggers.append(
        IriTrigger(
          triggerIri,
          triggerSolution.getLiteral("label").getLexicalForm,
          triggerSolution.getLiteral("comment").getLexicalForm,
          triggerSolution.getLiteral("patterns").getLexicalForm.split("\t"),
          validatorReferences.toArray
        )
      )
    }
    iriTriggers.toArray
  }

  private def loadIriValidators(m_tests: Model): Array[IriValidator] = {

    val validatorsQuery = QueryFactory.create(iriValidatorQueryStr())
    val validatorsResultSet = QueryExecutionFactory.create(validatorsQuery, m_tests).execSelect()

    val iriValidators = ArrayBuffer[IriValidator]()
    var arrayIndexCnt = 0

    while (validatorsResultSet.hasNext) {

      val validatorSolution = validatorsResultSet.next()
      val validatorIri = validatorSolution.getResource("validator").getURI
      validatorReferencesToIndexMap.put(validatorIri,arrayIndexCnt)

      iriValidators.append(
        IriValidator(
          validatorIri,
          validatorSolution.getLiteral("hasScheme").getLexicalForm,
          validatorSolution.getLiteral("hasQuery").getLexicalForm.toBoolean,
          validatorSolution.getLiteral("hasFragment").getLexicalForm.toBoolean,
          validatorSolution.getLiteral("doesNotContainCharacters").getLexicalForm.split("\t").map(_.charAt(0))
        )
      )
      arrayIndexCnt += 1
    }
    iriValidators.toArray
  }
}