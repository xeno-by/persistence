package scala.reflect.persistence

import scala.tools.nsc.{ Global, Phase, SubComponent }
import scala.tools.nsc.plugins.{ Plugin => NscPlugin, PluginComponent => NscPluginComponent }
import scala.language.postfixOps
import scala.annotation.tailrec
import java.io.DataOutputStream
import java.io.FileOutputStream

class Plugin(val global: Global) extends NscPlugin {
  import global._
  import Implicits._

  val name = "persistence"
  val description = """Persists typed ASTs of the entire program.
  For more information visit https://github.com/scalareflect/persistence"""
  val components = List[NscPluginComponent](PluginComponent) // Might change name

  private object PluginComponent extends NscPluginComponent {
    import global._
    val global = Plugin.this.global

    override val runsAfter = List("typer")
    val phaseName = "persist"

    def newPhase(prev: Phase) = new StdPhase(prev) {
      def apply(unit: CompilationUnit) {
        /* TODO: remove those (there for test) */
        val decomposedTree = new TreeDecomposer()(unit body)
        val recomposedTree = new TreeRecomposer()(decomposedTree)
        println("Original:")
        println(unit body)
        println("Recomposed:")
        println(recomposedTree)
        /*val lzwComp = new LzwCompressor(new DataOutputStream(new FileOutputStream("test.cmp")))*/
        /*new NameCompressor(lzwComp)(decomposedTree namesBFS)*/
        /*new SymbolCompressor(lzwComp)(decomposedTree symbBFS)*/
        /*lzwComp.flush*/
        /* TODO: implement this */
      }
    }

    /*Wrapper for treeDecomposer's function*/
    case class DecomposedTree(tree: Node, namesBFS: Map[Name, List[Int]], symbBFS: Map[Symbol, List[Int]], typesBFS: Map[Type, List[Int]])

    /* Return a simplified tree along with maps of Names / Symbols / Types zipped with occurrences in BFS order */
    class TreeDecomposer extends (Tree => DecomposedTree) {
      def apply(tree: Tree): DecomposedTree = {
        var nameList: RevList[Name] = List()
        var symbolList: RevList[Symbol] = List()
        var typeList: RevList[Type] = List()
        /* Traverse the tree, save names, type, symbols into corresponding list
         * and replace them in the tree by default values*/
        @tailrec def loop(trees: List[Tree], dict: Map[Tree, Node]): Map[Tree, Node] = trees match {
          case Nil => dict
          case x :: xs =>
            symbolList :+= x.symbol
            typeList :+= x.tpe
            val res = x match {
              case PackageDef(pid, stats) =>
                Node(AstTag.PackageDef, dict(pid) :: (stats map (dict(_))))
              case ClassDef(mods, name, tparams, impl) =>
                nameList :+= name
                Node(AstTag.ClassDef, (tparams ::: List(impl) map (dict(_))))
              case ModuleDef(mods, name, impl) =>
                nameList :+= name
                Node(AstTag.ModuleDef, List(dict(impl)))
              case ValDef(mods, name, tpt, rhs) =>
                nameList :+= name
                Node(AstTag.ValDef, List(dict(tpt), dict(rhs)))
              case DefDef(mods, name, tparams, vparams, tpt, rhs) =>
                nameList :+= name
                val vnodes = vparams.map(_.map(dict(_))).flatMap(_ :+ Node.separator)
                Node(AstTag.DefDef, (tparams.map(dict(_)) ::: List(Node.separator) ::: vnodes ::: List(dict(tpt), dict(rhs))))
              case TypeDef(mods, name, tparams, rhs) =>
                nameList :+ name
                Node(AstTag.TypeDef, (tparams ::: List(rhs)) map (dict(_)))
              case LabelDef(name, params, rhs) =>
                nameList :+= name
                Node(AstTag.LabelDef, (params ::: List(rhs)) map (dict(_)))
              case Import(expr, selectors) =>
                Node(AstTag.Import, List(dict(expr)))
              case Template(parents, self, body) =>
                Node(AstTag.Template, (parents.map(dict(_)) ::: List(Node.separator, dict(self), Node.separator) ::: body.map(dict(_))))
              case Block(stats, expr) =>
                Node(AstTag.Block, (stats ::: List(expr)) map (dict(_)))
              case CaseDef(pat, guard, body) =>
                Node(AstTag.CaseDef, List(pat, guard, body) map (dict(_)))
              case Alternative(trees) =>
                Node(AstTag.Alternative, trees map (dict(_)))
              case Star(elem) =>
                Node(AstTag.Star, List(dict(elem)))
              case Bind(name, body) =>
                nameList :+= name
                Node(AstTag.Bind, List(dict(body)))
              case UnApply(fun, args) =>
                Node(AstTag.UnApply, fun :: args map (dict(_)))
              case ArrayValue(elemtpt, elems) =>
                Node(AstTag.ArrayValue, elemtpt :: elems map (dict(_)))
              case Function(vparams, body) =>
                Node(AstTag.Function, vparams ::: List(body) map (dict(_)))
              case Assign(lhs, rhs) =>
                Node(AstTag.Assign, List(lhs, rhs) map (dict(_)))
              case AssignOrNamedArg(lhs, rhs) =>
                Node(AstTag.AssignOrNamedArg, List(lhs, rhs) map (dict(_)))
              case If(cond, thenp, elsep) =>
                Node(AstTag.If, List(cond, thenp, elsep) map (dict(_)))
              case Match(selector, cases) =>
                Node(AstTag.Match, selector :: cases map (dict(_)))
              case Return(expr) =>
                Node(AstTag.Return, List(dict(expr)))
              case Try(block, catches, finalizer) =>
                Node(AstTag.Try, block :: catches ::: List(finalizer) map (dict(_)))
              case Throw(expr) =>
                Node(AstTag.Throw, List(dict(expr)))
              case New(tpt) =>
                Node(AstTag.New, List(dict(tpt)))
              case Typed(expr, tpt) =>
                Node(AstTag.Typed, List(expr, tpt) map (dict(_)))
              case TypeApply(fun, args) =>
                Node(AstTag.TypeApply, fun :: args map (dict(_)))
              case Apply(fun, args) =>
                Node(AstTag.Apply, fun :: args map (dict(_)))
              case ApplyDynamic(qual, args) =>
                Node(AstTag.ApplyDynamic, qual :: args map (dict(_)))
              case This(qual) =>
                nameList :+= qual
                Node(AstTag.This, Nil)
              case Select(qualifier, selector) =>
                nameList :+= selector
                Node(AstTag.Select, List(dict(qualifier)))
              case Ident(name) =>
                nameList :+= name
                Node(AstTag.Ident, Nil)
              case ReferenceToBoxed(ident) =>
                Node(AstTag.ReferenceToBoxed, List(dict(ident)))
              case Literal(value) =>
                //Literal(value) /* TODO : what do we do with values ? Can keep them as is ? */
                Node(AstTag.Literal, value) //TODO HEY what do we do here ?
              case Annotated(annot, arg) =>
                Node(AstTag.Annotated, List(annot, arg) map (dict(_)))
              case SingletonTypeTree(ref) =>
                Node(AstTag.SingletonTypeTree, List(dict(ref)))
              case SelectFromTypeTree(qualifier, selector) =>
                nameList :+= selector
                Node(AstTag.SelectFromTypeTree, List(dict(qualifier)))
              case CompoundTypeTree(templ) =>
                Node(AstTag.CompoundTypeTree, List(dict(templ)))
              case AppliedTypeTree(tpt, args) =>
                Node(AstTag.AppliedTypeTree, tpt :: args map (dict(_)))
              case TypeBoundsTree(lo, hi) =>
                Node(AstTag.TypeBoundsTree, List(lo, hi) map (dict(_)))
              case ExistentialTypeTree(tpt, whereClauses) =>
                Node(AstTag.ExistentialTypeTree, tpt :: whereClauses map (dict(_)))
              case t: TypeTree =>
                Node(AstTag.TypeTree, Nil)
              case Super(qual, mix) =>
                nameList :+= mix
                Node(AstTag.Super, List(dict(qual)))
              case _ => sys.error(x.getClass().toString()) /* TODO : remove */
            }
            loop(xs, dict + (x -> res))
        }
              
        val newTree = loop(tree flattenBFS, Map((EmptyTree -> Node.empty)))(tree)
        DecomposedTree(newTree, nameList.zipWithIdxs, symbolList.zipWithIdxs, typeList.zipWithIdxs)
      }
    }
    class SymbolDecomposer { /* TODO */ }

    class NameCompressor(comp: LzwCompressor) extends (Map[Name, List[Int]] => Unit) {
      /* TODO: can we have null names ? If yes, this would crash */
      def apply(namesBFS: Map[Name, List[Int]]) { /* TODO: This is just a tentative encoding as string */
        var flags: List[Int] = List()
        val nms = namesBFS.:\("") {
          (nm, acc) =>
            val p = acc + nm._1.toString + "(" + nm._2.mkString(",") + ")"
            (if (nm._1.isTermName) flags :+= 1 else flags :+= 0)
            p
        } + "#" + createTag(flags).mkString(",") + "#"
        comp(nms)
        println(nms)
      }
      /*Returns the tags grouped by 8 encoded as char*/
      def createTag(l: List[Int]): List[Char] =
        (l.grouped(8).toList).map(x => bitsToChar(x.zipWithIndex, 0))

      /*Converts sequence of bits to char*/
      def bitsToChar(l: List[(Int, Int)], acc: Byte): Char = l match {
        case Nil => acc.toChar
        case x :: xs => bitsToChar(xs, (acc + (x._1 << x._2)).toByte)
      }
    }

    class SymbolCompressor(comp: LzwCompressor) extends (Map[Symbol, List[Int]] => Unit) {
      /* TODO: find what to store here. Use pickling ? */
      /* TODO: use the LzwCompressor to compress it using the same dict as before */
      /* TODO: encode properly the symbols, e.g. as string */
      /* TODO: the names here are the same as in the AST, no need to store them twice */
      /* TODO: the types here are the same as in the AST, no need to store them twice */
      def apply(symbBFS: Map[Symbol, List[Int]]) = { ??? }
    }
    class TypeCompressor { /* TODO */ }

    /* Generate a list of trees in BFS order */
    implicit class TreeToBFS(tree: Tree) {
      def flattenBFS = {
        @tailrec
        def loop(queue: List[Tree], acc: RevList[Tree]): RevList[Tree] = queue match {
          case expr :: exprs => loop(exprs ::: expr.children, expr.children.reverse ::: acc)
          case Nil => acc
        }
        loop(tree :: Nil, tree :: Nil)
      }
    }

    /* Note that for test purposes, we put this class in the plugin. */
    class TreeRecomposer extends (DecomposedTree => Tree) {
      def apply(decomp: DecomposedTree): Tree = {
        var nameList: RevList[Name] = decomp.namesBFS.unzipWithIdxs
        var symbolList: RevList[Symbol] = decomp.symbBFS.unzipWithIdxs
        var typeList: RevList[Type] = decomp.typesBFS.unzipWithIdxs
        @tailrec def loop(trees: List[Node], dict: Map[Node, Tree]): Map[Node, Tree] = trees match {
          case Nil => dict
          case x :: xs =>
            val res = x.tpe match {
              case AstTag.PackageDef =>
                PackageDef(dict(x.children.head).asInstanceOf[RefTree], x.children.tail map (dict(_)))
              case AstTag.ClassDef =>
                val nm = fetchName.asInstanceOf[TypeName] /* Need to fetch name first to avoid swap with name of modifier */
                ClassDef(null, nm, x.children.firsts map (dict(_).asInstanceOf[TypeDef]), dict(x.children.last).asInstanceOf[Template])
              case AstTag.ModuleDef =>
                val nm = fetchName.asInstanceOf[TermName]
                ModuleDef(null, nm, dict(x.children.head).asInstanceOf[Template])
              case AstTag.ValDef =>
                val nm = fetchName.asInstanceOf[TermName]
                ValDef(null, nm, dict(x.children.head), dict(x.children.last))
              case AstTag.DefDef =>
                val params = x.children.takeWithoutLasts(2).splitOn(_ == Node.separator)
                val vparams = params.tail.map(x => x.map(dict(_).asInstanceOf[ValDef]))
                val nm = fetchName.asInstanceOf[TermName]
                DefDef(null, nm, params.head.map(dict(_).asInstanceOf[TypeDef]), vparams, dict(x.children.firsts.last), dict(x.children.last))
              case AstTag.TypeDef =>
                val nm = fetchName.asInstanceOf[TypeName]
                TypeDef(null, nm, x.children.firsts map (dict(_).asInstanceOf[TypeDef]), dict(x.children.last))
              case AstTag.LabelDef =>
                LabelDef(fetchName.asInstanceOf[TermName], x.children.firsts map (dict(_).asInstanceOf[Ident]), dict(x.children.last))
              case AstTag.Import =>
                Import(dict(x.children.head), null)
              case AstTag.Template =>
                val children = x.children.splitOn(c => c.tpe == AstTag.Separator).map(_.map(dict(_)))
                Template(children.head, children(1).head.asInstanceOf[ValDef], children.last)
              case AstTag.Block =>
                Block(x.children.firsts map (dict(_)), dict(x.children.last))
              case AstTag.CaseDef =>
                CaseDef(dict(x.children.head), dict(x.children(1)), dict(x.children.last))
              case AstTag.Alternative =>
                Alternative(x.children map (dict(_)))
              case AstTag.Star =>
                Star(dict(x.children.head))
              case AstTag.Bind =>
                Bind(fetchName, dict(x.children.head))
              case AstTag.UnApply =>
                UnApply(dict(x.children.head), x.children.tail map (dict(_)))
              case AstTag.ArrayValue =>
                ArrayValue(dict(x.children.head), x.children.tail map (dict(_)))
              case AstTag.Function =>
                Function(x.children.firsts map (dict(_).asInstanceOf[ValDef]), dict(x.children.last))
              case AstTag.Assign =>
                Assign(dict(x.children.head), dict(x.children.last))
              case AstTag.AssignOrNamedArg =>
                AssignOrNamedArg(dict(x.children.head), dict(x.children.last))
              case AstTag.If =>
                If(dict(x.children.head), dict(x.children(1)), dict(x.children.last))
              case AstTag.Match =>
                Match(dict(x.children.head), x.children.tail map (dict(_).asInstanceOf[CaseDef]))
              case AstTag.Return =>
                Return(dict(x.children.head))
              case AstTag.Try =>
                Try(dict(x.children.head), x.children.tail.firsts map (dict(_).asInstanceOf[CaseDef]), dict(x.children.last))
              case AstTag.Throw =>
                Throw(dict(x.children.head))
              case AstTag.New =>
                New(dict(x.children.head))
              case AstTag.Typed =>
                Typed(dict(x.children.head), dict(x.children.last))
              case AstTag.TypeApply =>
                TypeApply(dict(x.children.head), x.children.tail map (dict(_)))
              case AstTag.Apply =>
                Apply(dict(x.children.head), x.children.tail map (dict(_)))
              case AstTag.ApplyDynamic =>
                ApplyDynamic(dict(x.children.head), x.children.tail map (dict(_)))
              case AstTag.This =>
                This(fetchName.asInstanceOf[TypeName])
              case AstTag.Select =>
                Select(dict(x.children.head), fetchName)
              case AstTag.Ident =>
                Ident(fetchName)
              case AstTag.ReferenceToBoxed =>
                ReferenceToBoxed(dict(x.children.head).asInstanceOf[Ident])
              case AstTag.Literal =>
                Literal(x.value.get.asInstanceOf[Constant]) /* TODO : value */
              case AstTag.Annotated =>
                Annotated(dict(x.children.head), dict(x.children.last))
              case AstTag.SingletonTypeTree =>
                SingletonTypeTree(dict(x.children.head))
              case AstTag.SelectFromTypeTree =>
                SelectFromTypeTree(dict(x.children.head), fetchName.asInstanceOf[TypeName])
              case AstTag.CompoundTypeTree =>
                CompoundTypeTree(dict(x.children.head).asInstanceOf[Template])
              case AstTag.AppliedTypeTree =>
                AppliedTypeTree(dict(x.children.head), x.children.tail map (dict(_)))
              case AstTag.TypeBoundsTree =>
                TypeBoundsTree(dict(x.children.head), dict(x.children.last))
              case AstTag.ExistentialTypeTree =>
                ExistentialTypeTree(dict(x.children.head), x.children.tail map (dict(_).asInstanceOf[MemberDef]))
              case AstTag.TypeTree =>
                TypeTree()
              case AstTag.Super =>
                Super(dict(x.children.head), fetchName.asInstanceOf[TypeName])
              case AstTag.EmptyTree => EmptyTree
              case _ => sys.error(x.getClass().toString()) /* TODO : remove */
            }
            /* TODO: cleaner */
            if (x.tpe != AstTag.EmptyTree) {
              if (typeList.head != null) res.setType(typeList.head)
              typeList = typeList.tail
              if (symbolList.head != null && x.tpe != AstTag.TypeTree) res.setSymbol(symbolList.head) /* TODO: cannot set symbols to TypeTree, figure out */
              symbolList = symbolList.tail
            }
            loop(xs, dict + (x -> res))
        }
        
        def fetchName = {
          val ret = nameList.head
          nameList = nameList.tail
          ret
        }
        loop(decomp.tree.flattenBFS.filter(x => x.tpe != AstTag.Separator), Map((Node.empty -> EmptyTree)))(decomp.tree)
      }
    }
  }
}
